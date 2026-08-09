(ns ligniteops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300):
  previous generators either were missing or mapped the wrong ledger/site
  fields (`:subject`/`:id` instead of this actor's real `:site-id`), so the
  committed sample page showed empty site ids and mis-attributed HARD holds.
  This namespace drives the REAL actor stack
  (`ligniteops.operation` -> `ligniteops.governor` -> `ligniteops.store`)
  through a scenario adapted from this repo's own `ligniteops.sim` demo
  driver (`clojure -M:dev:run`, ids match `ligniteops.store/demo-data`:
  lignite-mine-1/lignite-mine-2/lignite-mine-3), trimmed to a representative
  subset (three clean phase-3 auto-commits, one always-escalate
  safety-concern flag approved by a human, and three distinct HARD-hold
  reasons) and rendered deterministically — no invented numbers, no
  timestamps in the page content, byte-identical across reruns against the
  same seed.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [ligniteops.store :as store]
            [ligniteops.operation :as op]
            [ligniteops.advisor :as advisor]
            [langgraph.graph :as g]))

;; ----------------------------- harness -----------------------------

(def ^:private operator
  {:actor-id "op-1" :actor-role :shift-supervisor :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach: lignite-mine-1 clears three ops that all auto-commit
  clean at phase 3 (log-production-record, schedule-maintenance,
  coordinate-shipment); lignite-mine-1's safety-concern flag ALWAYS
  escalates (per `governor/always-escalate-ops`) even though clean and
  high-confidence, and is approved by a human shift supervisor;
  lignite-mine-2 (registered AND verified, so both checks below are
  exercised in isolation, not conflated with a site-verification
  failure) sees an advisor that drafts a non-`:propose` `:effect` (an
  attempted direct actuation) HARD-blocked on `:effect-not-propose`,
  then a proposal that has drifted into the permanently-excluded
  blasting/overburden/extraction-sequencing scope HARD-blocked on
  `:scope-excluded`; lignite-mine-3 (registered but NOT `:verified?` in
  the seed data) HARD-holds on `:site-unverified`. None of the HARD
  holds ever reach a human. Returns the resulting store — every field
  read by `render` below is real governor/store output, not a
  hand-typed copy."
  []
  (let [db (store/seed-db)
        actor (op/build db)
        rogue-actor (op/build db {:advisor (reify advisor/Advisor
                                             (-advise [_ st req]
                                               (assoc (advisor/infer st req) :effect :commit)))})]
    (exec! actor "lignite1-production" {:op :log-production-record :site-id "lignite-mine-1"
                                        :patch {:tonnage 5100 :shift "day"}})
    (exec! actor "lignite1-maintenance" {:op :schedule-maintenance :site-id "lignite-mine-1"
                                         :patch {:equipment "bucket-wheel-excavator-2" :window "2026-07-20"}})
    (exec! actor "lignite1-shipment" {:op :coordinate-shipment :site-id "lignite-mine-1"
                                      :patch {:carrier "rail-co-1" :tonnage 5100}})

    (exec! actor "lignite1-safety" {:op :flag-safety-concern :site-id "lignite-mine-1"
                                    :patch {:concern "subsidence crack observed near pit rim" :confidence 0.95}})
    (approve! actor "lignite1-safety")

    (exec! rogue-actor "lignite2-rogue-shipment" {:op :coordinate-shipment :site-id "lignite-mine-2"
                                                  :patch {:carrier "rail-co-1"}})

    (exec! actor "lignite2-scope" {:op :schedule-maintenance :site-id "lignite-mine-2"
                                   :out-of-scope? true :patch {}})

    (exec! actor "lignite3-production" {:op :log-production-record :site-id "lignite-mine-3"
                                        :patch {:tonnage 100}})
    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger site-id]
  (last (filter #(= (:site-id %) site-id) ledger)))

(defn- status-cell [ledger site-id]
  (let [f (last-fact-for ledger site-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (case rule
          :site-unverified "<span class=\"critical\">HARD hold &middot; unverified site</span>"
          :effect-not-propose "<span class=\"critical\">HARD hold &middot; direct-actuation attempt</span>"
          :scope-excluded "<span class=\"critical\">HARD hold &middot; scope-excluded</span>"
          (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>")))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- site-row [ledger {:keys [site-id name registered? verified?]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc site-id) (esc name)
          (if (and registered? verified?)
            "<span class=\"ok\">registered &amp; verified</span>"
            "<span class=\"warn\">registered, unverified</span>")
          (status-cell ledger site-id)))

(defn- ledger-row [{:keys [t op site-id disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc site-id)
          (esc (or (some->> basis (map #(if (keyword? %) (name %) (str %))) (str/join ", "))
                   (some-> disposition name)
                   ""))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own closed op contract
  ;; (README `Ops` table, `ligniteops.governor`/`ligniteops.phase`) —
  ;; documentation of fixed behavior, not runtime telemetry, so it is
  ;; legitimately hand-described rather than derived from a live run.
  ["        <tr><td><code>:log-production-record</code></td><td><span class=\"ok\">phase-3 auto when clean</span></td></tr>"
   "        <tr><td><code>:schedule-maintenance</code></td><td><span class=\"ok\">phase-3 auto when clean</span></td></tr>"
   "        <tr><td><code>:coordinate-shipment</code></td><td><span class=\"ok\">phase-3 auto when clean</span></td></tr>"
   "        <tr><td><code>:flag-safety-concern</code></td><td><span class=\"warn\">ALWAYS human approval &middot; never auto, any phase</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        sites (store/all-sites db)
        site-rows (str/join "\n" (map (partial site-row ledger) sites))
        ledger-rows (str/join "\n" (map ledger-row ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-0520 &middot; lignite-mining operations coordination</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Lignite mining operations coordination (ISIC 0520) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · never touches extraction/blasting/mine-safety-authority decisions</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Mine sites</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>ligniteops.store</code> via <code>ligniteops.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Site</th><th>Name</th><th>Registration status</th><th>Last coordination status</th></tr></thead>\n"
     "      <tbody>\n"
     site-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (LigniteMiningGovernor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. Extraction sequencing, blasting/drilling/cutting schedules, overburden-removal sequencing and mine-safety-authority decisions are permanently out of scope — see governor scope-exclusion.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Site</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        out-file (java.io.File. out)
        db (run-demo!)
        html (render db)]
    (when-let [parent (.getParentFile out-file)]
      (.mkdirs parent))
    (spit out-file html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/coordination-log db)) "committed coordination records )")))
