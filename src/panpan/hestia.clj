(ns panpan.hestia
  (:require
    [clojure.string    :as str]
    [jubot.adapter     :as ja]
    [jubot.handler     :as jh]
    [jubot.brain       :as jb]
    [jubot.scheduler   :as js]
    [panpan.util.match :refer :all]
    [panpan.toggl.api  :as toggl]))

(def ^:const NAME "ヘスティア") ; {{{
(def ^:const ICON "https://dl.dropboxusercontent.com/u/14918307/slack_icon/hestia.png")
(def ^:private out #(do (ja/out (apply str %&) :as NAME :icon-url ICON) nil))
(def ^:private pre #(str "```\n" % "\n```")) ; }}}

(def ^:const WORKSPACE_ID 141468)
(def ^:const REST_PID 3141045)
(def ^:const WARN_SEC (* 25 60)) ; 25 min
(def ^:const REST_WARN_SEC (* 5 60)) ; 5 min
(def ^:const RUNNING_KEY "toggl_running")
(def ^:const STOP_WARN_KEY "toggl_stop_warn")

(def ^:const MESSAGES ; {{{
  {:start-entry
   ["了解だよ！"
    "無理するなよ！"
    ]
   :stop-entry
   ["お疲れ！"
    "お疲れ様！"
    ]
   :delete-entry
   ["おっと、了解だよ！"
    "おっと了解！"
    ]
   :no-entry-to-delete
   ["ん？何が違うんだい？"]
   :start-rest
   ["オッケーだよ！"
    ]
   :reply
   ["ちょっとまって"
    ]
   :rest-warn
   ["そろそろ休憩終わりじゃないかい？"
    "そろそろ作業に戻るかい？"
    ]
   :warn
   ["そろそろ休憩したらどうだい？"
    "適度に休憩しないと集中できないぞ？"
    ]
   :stop-warn
   ["(わかったよ)"
    "(了解だよ)"
    ]
   :thanks
   ["どういたしまして！"
    "君のためだからね！"
    ]
   :sorry
   ["わかればいいんだよ"
    "次から気をつけるんだよ？"
    ]
   }) ; }}}

(defn- desc->pid ; {{{
  [desc]
  (jh/regexp {:text desc}
    #"cont"  (fn [& _] "870449")
    #"tdsei" (fn [& _] "8276549")
    #"sso"   (fn [& _] "3141182")
    #"ple"   (fn [& _] "3146901")
    #"(MTG|mtg|打ち合わせ|ミーティング)"
    (fn [& _] "3141739")
    #"(休憩|きゅうけい)"
    (fn [& _] "3141045")
    #"(ゲーム|げーむ)"
    (fn [& _] "9863311")
    #".*"
    (fn [& _] "8256158"))) ; }}}

(defn- running? [] (some? (jb/get RUNNING_KEY)))
(defn- warn? [] (nil? (jb/get STOP_WARN_KEY)))

(defn hestia-handler
  "^(.+?)\\s*(を)?(開始|始め|はじめ)          - toggl開始
   ^(再開|さいかい)                          - toggl再開
   (終了|終わ|おわた|完了)$                  - toggl停止
   ^(神様|神さま).*(違い|違う|消して|削除)   - toggl削除
   ^(神様|神さま).*(休憩|一休み)             - toggl休憩エントリー開始
  "
  [{:keys [user] :as arg}]
  (jh/regexp arg
    #"^(.+?)\s*(を)?(開始|始め|はじめ)"
    (matchfn [desc]
      (toggl/start-entry desc :pid (desc->pid desc))
      (jb/set RUNNING_KEY "true")
      (jb/set STOP_WARN_KEY nil)
      (->> MESSAGES :start-entry rand-nth (out "@" user " ")))
    #"^(再開|さいかい)"
    (matchfn []
      (when-let [entry (some->> (toggl/get-last-entries 2)
                                (drop-while #(= REST_PID (:pid %)))
                                first)]
        (hestia-handler
          (assoc arg :text (str (:description entry) "開始")))
        nil))
    #"(終了|終わ|おわた|完了)$"
    (matchfn []
      (when (and (running?) (toggl/stop-entry))
        (jb/set RUNNING_KEY nil)
        (jb/set STOP_WARN_KEY nil)
        (->> MESSAGES :stop-entry rand-nth (out "@" user " "))))
    #"^(神様|神さま).*(違い|違う|消して|削除)"
    (matchfn []
      (let [key (if (toggl/delete-entry) :delete-entry :no-entry-to-delete)]
        (jb/set RUNNING_KEY nil)
        (jb/set STOP_WARN_KEY nil)
        (->> MESSAGES
             key
             rand-nth
             (out "@" user " "))))
    #"^(神様|神さま).*(休憩|一休み)"
    (matchfn []
      (->> MESSAGES :start-rest rand-nth (out "@" user " "))
      (jb/set RUNNING_KEY "true")
      (jb/set STOP_WARN_KEY nil)
      (toggl/start-entry "休憩" :pid REST_PID)
      nil)
    #"^(神様|神さま).*(静か)"
    (matchfn []
      (when (running?)
        (jb/set STOP_WARN_KEY "true")
        (->> MESSAGES :stop-warn rand-nth (out "@" user " "))))

    #"^(神様|神さま).*ありがと"
    (matchfn []
      (->> MESSAGES :thanks rand-nth (out "@" user " ")))

    #"^(神様|神さま).*(ごめん|ゴメン)"
    (matchfn []
      (->> MESSAGES :sorry rand-nth (out "@" user " ")))

    #"^(神様|神さま).*プロジェクト"
    (matchfn []
      (->> MESSAGES :reply rand-nth (out "@" user " "))
      (->> (toggl/get-projects WORKSPACE_ID)
           (map #(str (:name %) " (" (:id %) ")"))
           (str/join "\n")
           pre out))
    ))

(def hestia-schedule
  (js/schedules
    "0 /5 * * * * *"
    #(when (and (running?) (warn?))
       (when-let [{:keys [pid sec]} (toggl/get-running-entry)]
         (cond
           (and (= pid REST_PID) (> sec REST_WARN_SEC))
           (->> MESSAGES :rest-warn rand-nth (out "@uochan "))

           (> sec WARN_SEC)
           (->> MESSAGES :warn rand-nth (out "@uochan ")))))
    ))

;(defn test-handler
;  [arg]
;  (jh/regexp arg
;    #"kamitest" (fn [& _]
;                  (jb/set RUNNING_KEY "true")
;                  ((first hestia-schedule))
;                  (jb/set RUNNING_KEY nil)
;                  nil
;                  )))
