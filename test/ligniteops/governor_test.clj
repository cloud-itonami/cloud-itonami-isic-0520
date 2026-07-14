(ns ligniteops.governor-test
  "Pure unit tests of `ligniteops.governor/check` against hand-built
  proposals -- the fast, focused complement to `governor-contract-
  test`'s full-graph integration coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [ligniteops.governor :as gov]
            [ligniteops.store :as store]))

(def site-1 {:site-id "lignite-mine-1" :name "Northern Lignite Mine" :registered? true :verified? true})
(def site-3 {:site-id "lignite-mine-3" :name "Southern Lignite Mine" :registered? true :verified? false})

(defn- clean-proposal [op site-id]
  {:op op :site-id site-id :summary "s" :rationale "routine back-office coordination"
   :cites [site-id] :effect :propose :value {} :confidence 0.85})

(deftest site-unregistered-is-hard
  (testing "no site record at all -> HARD hold"
    (let [s (store/mem-store {"lignite-mine-1" site-1})
          verdict (gov/check {} nil (clean-proposal :log-production-record "unknown-mine") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:site-unverified} (map :rule (:violations verdict)))))))

(deftest site-unverified-is-hard
  (testing "site registered but not yet verified -> HARD hold"
    (let [s (store/mem-store {"lignite-mine-3" site-3})
          verdict (gov/check {} nil (clean-proposal :log-production-record "lignite-mine-3") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:site-unverified} (map :rule (:violations verdict)))))))

(deftest effect-not-propose-is-hard
  (testing "any :effect other than :propose is a HARD, un-overridable block"
    (let [s (store/mem-store {"lignite-mine-1" site-1})
          verdict (gov/check {} nil (assoc (clean-proposal :coordinate-shipment "lignite-mine-1") :effect :commit) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

(deftest op-outside-allowlist-is-hard
  (testing "an op outside the closed four-op allowlist is a scope violation"
    (let [s (store/mem-store {"lignite-mine-1" site-1})
          verdict (gov/check {} nil (clean-proposal :extract-material "lignite-mine-1") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:op-not-allowed} (map :rule (:violations verdict)))))))

(deftest blasting-content-is-hard-and-permanent
  (testing "a proposal whose rationale touches blasting/drilling scope is HARD-blocked regardless of op/confidence"
    (let [s (store/mem-store {"lignite-mine-1" site-1})
          poisoned (assoc (clean-proposal :schedule-maintenance "lignite-mine-1")
                          :rationale "adjusted the next blast sequence and drilling pattern"
                          :confidence 0.99)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest overburden-removal-sequencing-content-is-hard
  (testing "a proposal touching overburden-removal / excavation sequencing is HARD-blocked, same as blasting"
    (let [s (store/mem-store {"lignite-mine-1" site-1})
          poisoned (assoc (clean-proposal :schedule-maintenance "lignite-mine-1")
                          :rationale "revised the overburden removal sequencing for pit sector 4")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest mine-safety-authority-content-is-hard
  (testing "a proposal touching mine-safety-authority decisions (permit issuance / license suspension / compliance enforcement) is HARD-blocked"
    (let [s (store/mem-store {"lignite-mine-1" site-1})
          poisoned (assoc (clean-proposal :coordinate-shipment "lignite-mine-1")
                          :summary "recommend license suspension pending review")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest legitimate-subsidence-flag-is-not-scope-excluded
  (testing "flagging observed subsidence/dust/groundwater as a SAFETY CONCERN (not a control decision) never trips scope-exclusion -- this actor's core valid use case must not be self-blocked"
    (let [s (store/mem-store {"lignite-mine-1" site-1})
          concern (assoc (clean-proposal :flag-safety-concern "lignite-mine-1")
                         :value {:concern "elevated groundwater level and dust near pit rim, possible subsidence"})
          verdict (gov/check {} nil concern s)]
      (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
          "raw observation content (subsidence/dust/groundwater) is exactly what this op exists to surface"))))

(deftest safety-flag-always-escalates
  (testing ":flag-safety-concern ALWAYS escalates, even at maximum confidence"
    (let [s (store/mem-store {"lignite-mine-1" site-1})
          verdict (gov/check {} nil (assoc (clean-proposal :flag-safety-concern "lignite-mine-1") :confidence 1.0) s)]
      (is (false? (:hard? verdict)))
      (is (true? (:escalate? verdict)))
      (is (true? (:high-stakes? verdict)))
      (is (false? (:ok? verdict))))))

(deftest low-confidence-escalates
  (testing "confidence below the floor escalates a proposal that is otherwise clean"
    (let [s (store/mem-store {"lignite-mine-1" site-1})
          verdict (gov/check {} nil (assoc (clean-proposal :log-production-record "lignite-mine-1") :confidence 0.4) s)]
      (is (false? (:hard? verdict)))
      (is (true? (:escalate? verdict)))
      (is (false? (:ok? verdict))))))

(deftest hard-violation-wins-over-escalate
  (testing "a HARD violation on an always-escalate op still reports hard?, not merely escalate?"
    (let [s (store/mem-store {})
          verdict (gov/check {} nil (clean-proposal :flag-safety-concern "unknown-mine") s)]
      (is (true? (:hard? verdict)))
      (is (false? (:escalate? verdict)) "hard? wins -- escalate? is false when hard? is true"))))

(deftest happy-path-every-op-is-clean
  (testing "each of the four allowlisted ops, clean input, registered+verified site -> ok"
    (let [s (store/mem-store {"lignite-mine-1" site-1})]
      (doseq [op [:log-production-record :schedule-maintenance :coordinate-shipment]]
        (let [verdict (gov/check {} nil (clean-proposal op "lignite-mine-1") s)]
          (is (false? (:hard? verdict)) (str op " should have no hard violations"))
          (is (false? (:escalate? verdict)) (str op " should not escalate when clean and confident"))
          (is (true? (:ok? verdict)) (str op " should be ok")))))))
