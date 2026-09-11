(ns ligniteops.advisor-test
  (:require [clojure.test :refer [deftest is testing]]
            [ligniteops.advisor :as advisor]
            [ligniteops.store :as store]))

(def db (store/seed-db))

(deftest every-op-proposal-is-always-propose-effect
  (testing "the advisor NEVER drafts a direct-actuation :effect -- always :propose"
    (doseq [op [:log-production-record :schedule-maintenance :flag-safety-concern :coordinate-shipment]]
      (let [p (advisor/infer db {:op op :site-id "lignite-mine-1" :patch {}})]
        (is (= :propose (:effect p)) (str op " must always propose, never actuate"))
        (is (= op (:op p)))
        (is (= "lignite-mine-1" (:site-id p)))
        (is (<= 0.0 (:confidence p) 1.0))
        (is (seq (:cites p)))))))

(deftest unrecognized-op-is-a-safe-noop
  (testing "an op outside the closed allowlist yields a safe zero-confidence :propose noop -- never a fabricated actuation"
    (let [p (advisor/infer db {:op :extract-material :site-id "lignite-mine-1" :patch {}})]
      (is (= :propose (:effect p)))
      (is (zero? (:confidence p))))))

(deftest safety-concern-confidence-passes-through-patch
  (testing "a caller-supplied confidence on a safety-concern proposal is honored (the governor, not the advisor, is what always escalates this op)"
    (let [p (advisor/infer db {:op :flag-safety-concern :site-id "lignite-mine-1" :patch {:concern "groundwater seepage" :confidence 0.99}})]
      (is (= 0.99 (:confidence p))))))

(deftest out-of-scope-hook-drafts-a-detectably-poisoned-proposal
  (testing "the :out-of-scope? test hook drafts content the governor's scope-exclusion scan must catch -- proves the failure mode is real and testable end to end"
    (let [p (advisor/infer db {:op :schedule-maintenance :site-id "lignite-mine-1" :patch {} :out-of-scope? true})]
      (is (= :propose (:effect p)))
      (is (re-find #"(?i)blast" (str (:summary p) (:rationale p)))))))

(deftest mock-advisor-routes-through-infer
  (let [a (advisor/mock-advisor)
        p (advisor/-advise a db {:op :coordinate-shipment :site-id "lignite-mine-1" :patch {:carrier "rail-1"}})]
    (is (= :coordinate-shipment (:op p)))
    (is (= :propose (:effect p)))))

(deftest trace-carries-decision-grounded-fields
  (let [request {:op :log-production-record :site-id "lignite-mine-1"}
        proposal (advisor/infer db request)
        t (advisor/trace request proposal)]
    (is (= :log-production-record (:op t)))
    (is (= "lignite-mine-1" (:site-id t)))
    (is (= (:confidence proposal) (:confidence t)))))
