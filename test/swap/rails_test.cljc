(ns swap.rails-test
  "Both rails, driven with response FIXTURES rather than live vendors: the plan
  builder, the fee wiring, the approve-only-when-needed rule, and — for
  THORChain — the refusal to build a transfer from a memo that does not match
  what was requested."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [swap.aggregator :as agg]
            [swap.core :as core]
            [swap.fee :as fee]
            [swap.thorchain :as tc]))

(def usdc {:chain "ETH" :chain-id 1 :asset "ETH.USDC" :symbol "USDC" :decimals 6
           :address "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"})
(def weth {:chain "ETH" :chain-id 1 :asset "ETH.WETH" :symbol "WETH" :decimals 18
           :address "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2"})
(def btc {:chain "BTC" :asset "BTC.BTC" :symbol "BTC" :decimals 8})
(def eth {:chain "ETH" :chain-id 1 :asset "ETH.ETH" :symbol "ETH" :decimals 18})

;; ══ aggregator rail ══

(def agg-intent
  (core/intent {:from usdc :to weth :amount "1.5" :taker "0xtaker"
                :slippage-bps 100 :fee-bps 30 :fee-recipient "0xfee"}))

(def zero-ex (get agg/adapters :zero-ex-v2))

(deftest aggregator-request-carries-the-fee-params
  (let [{:keys [url provider sell-units]} (agg/quote-request agg-intent zero-ex)]
    (is (= :zero-ex-v2 provider))
    (is (= "1500000" sell-units) "1.5 USDC at 6 decimals, exactly")
    (is (str/includes? url "sellAmount=1500000"))
    (is (str/includes? url "swapFeeBps=30"))
    (is (str/includes? url "swapFeeRecipient=0xfee"))
    (is (str/includes? url "swapFeeToken=0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2")
        "the fee is taken in the BUY token")
    (is (str/includes? url "slippageBps=100"))))

(deftest aggregator-request-omits-fee-params-when-no-fee
  (let [i (core/intent {:from usdc :to weth :amount "1" :taker "0xt"})
        {:keys [url]} (agg/quote-request i zero-ex)]
    (is (not (str/includes? url "swapFeeBps")))))

(deftest aggregator-refuses-cross-chain
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (agg/quote-request (core/intent {:from btc :to weth :amount "0.01"})
                                  zero-ex))
      "the aggregator rail is same-chain only"))

(def agg-body-needs-approve
  {"transaction" {"to" "0xdef1c0ded9bec7f1a1670819833240f027b25eff" "data" "0xdeadbeef" "value" "0" "gas" "210000"}
   "buyAmount" "600000000000000000"
   "minBuyAmount" "597000000000000000"
   "issues" {"allowance" {"spender" "0xdef1c0ded9bec7f1a1670819833240f027b25eff" "actual" "0"}}})

(def agg-body-has-allowance
  (assoc agg-body-needs-approve "issues" {"allowance" nil}))

(deftest aggregator-plan-includes-approve-when-allowance-short
  (let [req (agg/quote-request agg-intent zero-ex)
        q (agg/parse-quote agg-intent zero-ex agg-body-needs-approve req)
        [s1 s2] (:steps q)]
    (is (= 2 (count (:steps q))))
    (is (= :erc20-approve (:step/kind s1)))
    (is (= (:address usdc) (:to s1)) "approve is sent to the TOKEN, not the router")
    (is (str/starts-with? (:data s1) "0x095ea7b3") "approve(address,uint256) selector")
    (is (str/ends-with? (:data s1) "16e360")
        "1500000 encoded as hex 0x16e360, not as a decimal string")
    (is (str/includes? (:step/why s1) "1.5 USDC")
        "the reason is in the user's units, for a confirmation prompt")
    (is (= :evm-call (:step/kind s2)))
    (is (= "0xdef1c0ded9bec7f1a1670819833240f027b25eff" (:to s2)))
    (is (= "0xdeadbeef" (:data s2)))))

(deftest aggregator-plan-skips-approve-when-allowance-sufficient
  (let [req (agg/quote-request agg-intent zero-ex)
        q (agg/parse-quote agg-intent zero-ex agg-body-has-allowance req)]
    (is (= 1 (count (:steps q))) "no unnecessary approval transaction")
    (is (= :evm-call (:step/kind (first (:steps q)))))))

(deftest aggregator-plan-skips-approve-when-allowance-already-large
  (let [body (assoc-in agg-body-needs-approve ["issues" "allowance" "actual"]
                       "999999999999")
        req (agg/quote-request agg-intent zero-ex)
        q (agg/parse-quote agg-intent zero-ex body req)]
    (is (= 1 (count (:steps q))))))

(deftest aggregator-plan-approves-when-allowance-slightly-short
  (testing "1499999 < 1500000 — off-by-one must still approve"
    (let [body (assoc-in agg-body-needs-approve ["issues" "allowance" "actual"] "1499999")
          req (agg/quote-request agg-intent zero-ex)
          q (agg/parse-quote agg-intent zero-ex body req)]
      (is (= 2 (count (:steps q)))))))

(deftest aggregator-quote-normalizes-and-passes-check
  (let [req (agg/quote-request agg-intent zero-ex)
        q (agg/parse-quote agg-intent zero-ex agg-body-needs-approve req)]
    (is (= :aggregator (:rail q)))
    (is (= "600000000000000000" (:expected-out q)))
    (is (= "597000000000000000" (:min-out q)))
    (is (= :protocol-affiliate (:fee-mechanism q)))
    (is (= 30 (:fee-bps q)))
    (is (false? (:verified-adapter? q))
        "the shipped adapters are honestly marked unverified against a live key")
    (is (:ok? (core/check agg-intent q 1000)))))

(deftest aggregator-fails-loudly-on-unmapped-response
  (testing "a vendor field rename must not yield a quote with a nil min-out"
    (let [req (agg/quote-request agg-intent zero-ex)]
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                   (agg/parse-quote agg-intent zero-ex
                                    (dissoc agg-body-needs-approve "minBuyAmount")
                                    req))))))

;; ══ thorchain rail ══

(def tc-intent
  (core/intent {:from btc :to eth :amount "0.05"
                :destination "0xe6a30f4f3bad978910e2cbb4d97581f5b5a0ade0"
                :slippage-bps 300 :fee-bps 30 :fee-recipient "kb"}))

(deftest thorchain-request-uses-1e8-and-affiliate
  (let [{:keys [url amount-1e8]} (tc/quote-request tc-intent {:affiliate "kb"})]
    (is (= "5000000" amount-1e8) "0.05 in 1e8 fixed point")
    (is (str/includes? url "amount=5000000"))
    (is (str/includes? url "affiliate=kb"))
    (is (str/includes? url "affiliate_bps=30"))
    (is (str/includes? url "tolerance_bps=300"))))

(defn tc-body [memo]
  {"inbound_address" "bc1qvault"
   "memo" memo
   "expected_amount_out" "203529920800"
   "expiry" 2000
   "outbound_delay_seconds" 600
   "total_swap_seconds" 660
   "recommended_min_amount_in" "119000"
   "dust_threshold" "10000"})

(def good-memo
  "=:ETH.ETH:0xe6a30f4f3bad978910e2cbb4d97581f5b5a0ade0:200000000000/1/0:kb:30")

(deftest thorchain-plan-is-one-native-transfer
  (let [req (tc/quote-request tc-intent {})
        q (tc/parse-quote tc-intent req (tc-body good-memo))
        [step] (:steps q)]
    (is (= :thorchain (:rail q)))
    (is (= 1 (count (:steps q))))
    (is (= :native-transfer (:step/kind step)))
    (is (= "BTC" (:chain step)))
    (is (= "bc1qvault" (:to step)))
    (is (= "5000000" (:amount step)) "satoshis — 0.05 BTC at 8 decimals")
    (is (= good-memo (:memo step)))
    (is (str/includes? (:step/why step) "affiliate fee")
        "the fee is stated in the confirmation reason")))

(deftest thorchain-min-out-comes-from-the-memo-limit
  (let [req (tc/quote-request tc-intent {})
        q (tc/parse-quote tc-intent req (tc-body good-memo))]
    (is (= "200000000000" (:min-out q)) "the memo's LIM field is the on-chain guard")
    (is (:ok? (core/check tc-intent q 1000)))))

(deftest thorchain-missing-limit-is-reported-not-papered-over
  (testing "a memo with no limit means NO on-chain guard — must not default to expected-out"
    (let [memo "=:ETH.ETH:0xe6a30f4f3bad978910e2cbb4d97581f5b5a0ade0::kb:30"
          req (tc/quote-request tc-intent {})
          q (tc/parse-quote tc-intent req (tc-body memo))
          {:keys [ok? problems]} (core/check tc-intent q 1000)]
      (is (nil? (:min-out q)))
      (is (false? ok?))
      (is (some #(= :missing-min-out (:problem %)) problems)))))

(deftest thorchain-refuses-substituted-destination
  (let [attack "=:ETH.ETH:0xdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef:200000000000/1/0:kb:30"
        req (tc/quote-request tc-intent {})]
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (tc/parse-quote tc-intent req (tc-body attack)))
        "a quote endpoint must not be able to redirect the output")))

(deftest thorchain-refuses-dropped-affiliate-fee
  (let [memo "=:ETH.ETH:0xe6a30f4f3bad978910e2cbb4d97581f5b5a0ade0:200000000000/1/0"
        req (tc/quote-request tc-intent {})]
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (tc/parse-quote tc-intent req (tc-body memo))))))

(deftest thorchain-refuses-missing-inbound-address
  (let [req (tc/quote-request tc-intent {})]
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (tc/parse-quote tc-intent req
                                 (dissoc (tc-body good-memo) "inbound_address"))))))

(deftest thorchain-evm-inbound-goes-through-the-router
  (let [i (core/intent {:from eth :to btc :amount "1"
                        :destination "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"
                        :slippage-bps 300})
        req (tc/quote-request i {})
        memo "=:BTC.BTC:bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh:100000/1/0"
        q (tc/parse-quote i req (assoc (tc-body memo) "router" "0xdef1c0ded9bec7f1a1670819833240f027b25eff"))
        [step] (:steps q)]
    (is (= :evm-call (:step/kind step)))
    (is (= "0xdef1c0ded9bec7f1a1670819833240f027b25eff" (:router step)))
    (is (true? (:needs-router-calldata? step))
        "router calldata must come from the node, not a hard-coded ABI here")
    (is (= "1000000000000000000" (:amount step)) "wei, not 1e8")))

(deftest thorchain-preflight-flags-halted-chain-and-small-amounts
  (let [req (tc/quote-request tc-intent {})
        q (tc/parse-quote tc-intent req (tc-body good-memo))
        halted {"BTC" {:address "bc1qvault" :halted true}}
        fine {"BTC" {:address "bc1qvault" :halted false}}]
    (is (some #(= :chain-not-sendable (:problem %)) (tc/preflight tc-intent q halted)))
    (is (empty? (tc/preflight tc-intent q fine)))
    (testing "an amount below the node's recommended minimum is flagged"
      (let [tiny-intent (core/intent {:from btc :to eth :amount "0.00001"
                                      :destination "0xe6a30f4f3bad978910e2cbb4d97581f5b5a0ade0"
                                      :slippage-bps 300})
            tiny-memo "=:ETH.ETH:0xe6a30f4f3bad978910e2cbb4d97581f5b5a0ade0:1/1/0"
            tq (tc/parse-quote tiny-intent (tc/quote-request tiny-intent {})
                               (tc-body tiny-memo))]
        (is (some #(= :below-recommended-minimum (:problem %))
                  (tc/preflight tiny-intent tq fine)))))))

;; ══ fee accounting ══

(deftest fee-bps-to-fraction-and-split
  (is (= 0.003 (fee/bps->frac 30)))
  (let [{:keys [usd fee net]} (fee/split 100 30)]
    (is (= 100.0 usd))
    (is (< (abs (- 0.3 fee)) 1e-9))
    (is (< (abs (- 99.7 net)) 1e-9))))

(deftest fee-quote-fee-reports-mechanism-honestly
  (is (= {:bps 30 :mechanism :protocol-affiliate :recipient "0xfee" :preferred? true}
         (fee/quote-fee {:fee-bps 30 :fee-recipient "0xfee"} :protocol-affiliate)))
  (is (= :separate-transfer
         (:mechanism (fee/quote-fee {:fee-bps 30 :fee-recipient "0xfee"}
                                    :separate-transfer))))
  (is (false? (:preferred? (fee/quote-fee {:fee-bps 30 :fee-recipient "0xfee"}
                                          :separate-transfer))))
  (testing "no fee requested -> :none regardless of the mechanism offered"
    (is (= :none (:mechanism (fee/quote-fee {:fee-bps 0} :protocol-affiliate))))))

(deftest fee-ledger-entries-are-pending-until-confirmed
  (let [p (fee/pending-entry {:payer "did:key:zAlice" :usd 100 :bps 30
                              :chain "ethereum" :tx-id "0xtx"})
        c (fee/confirmed-entry {:payer "did:key:zAlice" :usd 100 :bps 30
                                :chain "ethereum" :tx-id "0xtx"})]
    (is (= :pending (:run/kind p)))
    (is (= :crypto-pending (:treasury/proof p)))
    (is (= :confirmed (:run/kind c)))
    (is (= :crypto-confirmed (:treasury/proof c)))
    (is (< (abs (- 0.3 (:treasury/fee c))) 1e-9))
    (is (< (abs (- 99.7 (:treasury/net c))) 1e-9))))
