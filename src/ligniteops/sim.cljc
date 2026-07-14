(ns ligniteops.sim
  "Demo driver -- `clojure -M:run`. Walks a clean production-record
  logging request through intake -> advise -> govern -> decide ->
  approval -> commit at phase 1 (assisted-production, always
  approval), then re-runs the same op at phase 3 (supervised-auto,
  clean + high confidence -> auto-commit), then a maintenance-
  scheduling request and a shipment-coordination request (also
  auto-commit clean at phase 3), then a safety-concern flag (ALWAYS
  escalates, at any phase -- approve, then commit), then HARD-hold
  scenarios: an unregistered site, a site registered but not yet
  verified, a proposal whose own `:effect` is not `:propose`, and a
  proposal that has drifted into the permanently-excluded overburden-
  removal/blasting/extraction-sequencing scope."
  (:require [langgraph.graph :as g]
            [ligniteops.advisor :as advisor]
            [ligniteops.store :as store]
            [ligniteops.operation :as op]))

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "shift-supervisor-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        operator-phase-1 {:actor-id "op-1" :actor-role :shift-supervisor :phase 1}
        operator-phase-3 {:actor-id "op-1" :actor-role :shift-supervisor :phase 3}
        actor (op/build db)]

    (println "== log-production-record lignite-mine-1 (phase 1, escalates -- human approves) ==")
    (println (exec-op actor "t1" {:op :log-production-record :site-id "lignite-mine-1"
                                  :patch {:tonnage 5100 :shift "day"}} operator-phase-1))
    (println (approve! actor "t1"))

    (println "== log-production-record lignite-mine-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t2" {:op :log-production-record :site-id "lignite-mine-1"
                                  :patch {:tonnage 5300 :shift "night"}} operator-phase-3))

    (println "== schedule-maintenance lignite-mine-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t3" {:op :schedule-maintenance :site-id "lignite-mine-1"
                                  :patch {:equipment "bucket-wheel-excavator-2" :window "2026-07-20"}} operator-phase-3))

    (println "== coordinate-shipment lignite-mine-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t4" {:op :coordinate-shipment :site-id "lignite-mine-1"
                                  :patch {:carrier "rail-co-1" :tonnage 5100}} operator-phase-3))

    (println "== flag-safety-concern lignite-mine-1 (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t5" {:op :flag-safety-concern :site-id "lignite-mine-1"
                                 :patch {:concern "subsidence crack observed near pit rim" :confidence 0.95}} operator-phase-3)]
      (println r)
      (println "-- human shift supervisor reviews & approves --")
      (println (approve! actor "t5")))

    (println "== log-production-record lignite-mine-9 (unregistered site -> HARD hold) ==")
    (println (exec-op actor "t6" {:op :log-production-record :site-id "lignite-mine-9"
                                  :patch {:tonnage 100}} operator-phase-3))

    (println "== log-production-record lignite-mine-3 (registered but unverified -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :log-production-record :site-id "lignite-mine-3"
                                  :patch {:tonnage 100}} operator-phase-3))

    (println "== coordinate-shipment lignite-mine-1, advisor attempts direct actuation (:effect :commit) -> HARD hold ==")
    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                (-advise [_ _ req]
                                                  (assoc (advisor/infer db req) :effect :commit)))})]
      (println (exec-op actor-direct "t8" {:op :coordinate-shipment :site-id "lignite-mine-1"
                                           :patch {:carrier "rail-co-1"}} operator-phase-3)))

    (println "== schedule-maintenance lignite-mine-1, advisor drifts into blasting/overburden scope -> HARD hold, permanent ==")
    (println (exec-op actor "t9" {:op :schedule-maintenance :site-id "lignite-mine-1"
                                  :out-of-scope? true
                                  :patch {}} operator-phase-3))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== committed coordination log ==")
    (doseq [r (store/coordination-log db)] (println r))))
