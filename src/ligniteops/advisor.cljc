(ns ligniteops.advisor
  "LigniteOpsAdvisor -- the *contained intelligence node* for the
  ISIC-0520 lignite-mining operations-coordination actor.

  It drafts exactly four kinds of back-office proposal from a closed
  allowlist: production-record logging, equipment-maintenance
  scheduling, safety-concern flagging (subsidence, dust, groundwater),
  and outbound-shipment coordination. CRITICAL: it is a smart-but-
  untrusted advisor. It returns a *proposal* (with a rationale + the
  fields it cited), never a committed record and NEVER a direct
  actuation -- every proposal's `:effect` is always `:propose`. Every
  output is censored downstream by `ligniteops.governor` before
  anything touches the SSoT.

  This advisor NEVER drafts extraction sequencing, blasting/drilling/
  cutting/overburden-removal schedules, or any mine-safety-authority
  decision (permit issuance, license suspension, compliance
  enforcement) -- those are permanently out of scope for this actor,
  not merely un-implemented. `ligniteops.governor`'s
  `scope-exclusion-violations` independently re-scans every proposal
  for exactly this failure mode (a compromised or confused advisor
  drifting into scope it must never touch) and HARD-holds it,
  regardless of confidence or op.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:op         kw             ; echoes the request op
     :site-id    str
     :summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the scope-exclusion gate
     :cites      [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect     :propose       ; ALWAYS :propose -- never a direct actuation
     :value      map            ; the draft payload a human/system would review
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.model :as model]))

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-production-record
  "Draft an output/tonnage production-record log entry. Pure logging of
  ALREADY-OCCURRED production data -- never a decision about how or
  when to extract."
  [_db {:keys [site-id patch]}]
  {:op         :log-production-record
   :site-id    site-id
   :summary    (str site-id " の産出量記録を提案: " (pr-str (keys patch)))
   :rationale  "入力された産出量データの記録提案のみ。新規事実の生成なし。"
   :cites      [site-id]
   :effect     :propose
   :value      (merge {:site-id site-id} patch)
   :confidence 0.93})

(defn- propose-maintenance
  "Draft an equipment-maintenance scheduling proposal (a calendar
  entry/work order draft, never a direct dispatch)."
  [_db {:keys [site-id patch]}]
  {:op         :schedule-maintenance
   :site-id    site-id
   :summary    (str site-id " の設備メンテナンス予定を提案: " (pr-str (keys patch)))
   :rationale  "設備保守スケジュールの提案のみ。実際の保守作業実施の判断は人間が行う。"
   :cites      [site-id]
   :effect     :propose
   :value      (merge {:site-id site-id} patch)
   :confidence 0.88})

(defn- propose-safety-concern
  "Surface a mine-safety concern (subsidence, dust, groundwater) for
  HUMAN triage. This op ALWAYS escalates in `ligniteops.governor` --
  never auto-committed at any phase (`ligniteops.phase`) -- regardless
  of how confident the advisor is that the concern is real or minor.
  The advisor itself makes NO safety determination; it only surfaces
  the observation."
  [_db {:keys [site-id patch]}]
  {:op         :flag-safety-concern
   :site-id    site-id
   :summary    (str site-id " の安全上の懸念を提起: " (pr-str (keys patch)))
   :rationale  "観測された懸念事象(地盤沈下・粉塵・地下水位変化等)の提起のみ。安全性の評価・是正措置の決定は行わない -- 常に人間審査が必要。"
   :cites      [site-id]
   :effect     :propose
   :value      (merge {:site-id site-id} patch)
   :confidence (get patch :confidence 0.9)})

(defn- propose-shipment
  "Draft outbound lignite-shipment coordination (loadout scheduling,
  carrier/consignee handoff paperwork draft) -- coordination only,
  never the physical loadout act itself."
  [_db {:keys [site-id patch]}]
  {:op         :coordinate-shipment
   :site-id    site-id
   :summary    (str site-id " の出荷調整を提案: " (pr-str (keys patch)))
   :rationale  "出荷調整(搬出スケジュール/運送業者引き渡し)案のみ。実際の搬出実施は人間が行う。"
   :cites      [site-id]
   :effect     :propose
   :value      (merge {:site-id site-id} patch)
   :confidence 0.9})

(defn- propose-out-of-scope
  "Test/failure-mode hook: drafts a proposal that touches a
  permanently-excluded scope area (blasting/extraction-sequencing/
  mine-safety-authority) so the governor's `scope-exclusion-
  violations` HARD block can be exercised directly, the same
  'exercise the failure mode directly' discipline every sibling
  actor's own sim/test suite uses. Never reachable from the closed op
  allowlist in normal operation -- only via the `:out-of-scope?`
  request flag."
  [_db {:keys [site-id patch]}]
  {:op         :schedule-maintenance
   :site-id    site-id
   :summary    (str site-id " の次回発破(blasting)順序の変更を提案")
   :rationale  "次回のoverburden removal区画のdrilling patternとblast sequenceを調整済み"
   :cites      [site-id]
   :effect     :propose
   :value      (merge {:site-id site-id} patch)
   :confidence 0.9})

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :site-id str :patch map ...}"
  [db {:keys [op out-of-scope?] :as request}]
  (cond
    out-of-scope?                          (propose-out-of-scope db request)
    (= op :log-production-record)          (propose-production-record db request)
    (= op :schedule-maintenance)           (propose-maintenance db request)
    (= op :flag-safety-concern)            (propose-safety-concern db request)
    (= op :coordinate-shipment)            (propose-shipment db request)
    :else {:op op :site-id (:site-id request)
           :summary "未対応の操作" :rationale (str "closed allowlist に無い操作: " op)
           :cites [] :effect :propose :value {} :confidence 0.0}))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

;; ----------------------------- real-LLM advisor (production seam) -----------------------------

(def ^:private system-prompt
  (str "あなたは褐炭(lignite)鉱山の運営コーディネーション助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。"
       "許可された操作は :log-production-record / :schedule-maintenance / "
       ":flag-safety-concern / :coordinate-shipment の4つのみです。"
       "採掘順序・発破・掘削/表土除去計画・鉱山保安機関の判断(許可発行/免許停止/"
       "コンプライアンス執行)には絶対に触れてはいけません。"
       "地盤沈下・粉塵・地下水位の懸念は flag-safety-concern で観測事実のみ"
       "提起し、評価や是正措置の決定は行いません。"
       "キー: :op :site-id :summary :rationale :cites :effect(常に :propose) "
       ":value :confidence(0..1)。"))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the governor escalates/holds --
  an LLM hiccup can never bypass governance."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :propose)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :propose :value {} :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ _st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n site: " (:site-id req)
                                              "\n patch: " (pr-str (:patch req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :advisor-proposal
   :op         (:op request)
   :site-id    (:site-id request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
