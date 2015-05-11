(ns panpan.persona
  (:require
    [clojure.string  :as str]
    [jubot.handler   :as handler]
    [jubot.brain     :as brain]
    [clj-docomo-dialogue.core :as dd]))

(def ^:const CONTEXT_KEY "docomo_dialogue_context")
(def ^:private api-key (System/getenv "DOCOMO_API_KEY"))

(defn- brain-key-values
  [& _]
  (let [ks          (brain/keys)
        key-max-len (apply max (map count ks))
        make-spaces #(str/join "" (repeat (- key-max-len (count %)) " "))]
    (->> ks
         (reduce
           (fn [res k]
             (conj res (str " * " k (make-spaces k) " : " (brain/get k))))
           [])
         (str/join "\n")
         (str "Key/Value\n"))))


(defn persona-hear-handler
  [{:keys [user] :as arg}]
  (handler/regexp
    arg
    #"ぬるぽ"
    (fn [_] "ガッ")

    #"(パンダ|ぱんだ)"
    (fn [_]
      (rand-nth ["呼んだ？" "なぁに？" "ん？" "はーい、え？違う？"
                 "こっち見んな" "？" "僕のこと？" "うるさいぞ"]))

    #"(なに|何)(たべ|食べ)(よう|ようかな|る?|る？|たい?|たい？)"
    (fn [_]
      (rand-nth ["牛丼" "パスタ" "カレー" "寿司" "蕎麦" "うどん" "定食"
                 "ファミレス" "ラーメン" "チャーハン" "餃子" "ハンバーグ"
                 "竹" "笹" "タケノコ" "豆腐" "オムライス"]))))

(defn panpan-handler
  [{:keys [text message-for-me?] :as arg}]
  (when message-for-me?
    (handler/regexp arg
      #"ping" (constantly "pong")
      #"^set (.+?) (.+?)$" (fn [{[_ k v] :match}] (brain/set k v) "OK")
      #"^get (.+?)$"       (fn [{[_ k]   :match}] (brain/get k))
      #"^brain$"           brain-key-values
      #".+"
      (fn [& _]
        (when-let [res (some-> api-key
                               (dd/talk text {;:t 30
                                              :context (brain/get CONTEXT_KEY)}))]
          (brain/set CONTEXT_KEY (:context res))
          (:utt res))))))
