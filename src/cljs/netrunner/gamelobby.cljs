(ns netrunner.gamelobby
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [om.core :as om :include-macros true]
            [sablono.core :as sab :include-macros true]
            [cljs.core.async :refer [chan put! <!] :as async]
            [clojure.string :refer [join]]
            [netrunner.auth :refer [authenticated avatar] :as auth]))

(def app-state (atom {:games []}))
(def socket-channel (chan))
(def join-channel (chan))
(def socket (.connect js/io (str js/iourl "/lobby")))

(go (while true
      (let [msg (<! socket-channel)]
        (case (:type msg)
          "game" (put! join-channel (:game msg))
          "games" (swap! app-state assoc :games (sort-by :date > (:games msg)))))))

(.on socket "netrunner" #(put! socket-channel (js->clj % :keywordize-keys true)))

(defn send [msg]
  (.emit socket "netrunner" (clj->js msg)))

(defn new-game [cursor owner]
  (authenticated
   (fn [user]
     (when-not (om/get-state owner :game)
       (om/set-state! owner :title (str (:username user) "'s game"))
       (om/set-state! owner :editing true)
       (-> ".game-title" js/$ .select)))))

(defn create-game [cursor owner]
  (authenticated
   (fn [user]
     (if (empty? (om/get-state owner :title))
       (om/set-state! owner :flash-message "Please fill a game title.")
       (do
         (om/set-state! owner :editing false)
         (send {:action "create" :title (om/get-state owner :title) :player user}))))))

(defn join-game [gameid cursor owner]
  (authenticated
   (fn [user]
     (send {:action "join" :gameid gameid :user user}))))

(defn start-game [owner])

(defn leave-game [owner]
  (send {:action "leave" :username (get-in @auth/app-state [:user :username])})
  (om/set-state! owner :in-game false)
  (om/set-state! owner :game nil))

(defn game-lobby [{:keys [games] :as cursor} owner]
  (reify
    om/IWillMount
    (will-mount [this]
      (go (while true
            (let [game (<! join-channel)]
              (om/set-state! owner :game game)))))

    om/IRenderState
    (render-state [this state]
      (sab/html
       [:div.lobby.panel.blue-shade
        [:div.games
         [:div.button-bar
          [:button {:class (if (:in-game state) "disabled" "")
                    :on-click #(new-game cursor owner)} "New game"]]
         (if (empty? games)
           [:h4 "No game"]
           (for [game games]
             [:div.gameline {:class (when (= (get-in state [:game :id]) (:id game)) "active")}
              (when-not (or (:game state) (:editing state) (= (count (:players game)) 2))
                (let [id (:id game)]
                  [:button.float-right {:on-click #(join-game id cursor owner)} "Join"]))
              [:h4 (:title game)]
              [:div
               (for [player (:players game)]
                 [:span.player
                  (om/build avatar player {:opts {:size 22}})
                  (:username player)])]]))]

        [:div.game-panel
         (if (:editing state)
           (do
             [:div
              [:div.button-bar
               [:button {:type "button" :on-click #(create-game cursor owner)} "Create"]
               [:button {:type "button" :on-click #(om/set-state! owner :editing false)} "Cancel"]]
              [:h4 "Title"]
              [:input.game-title {:on-change #(om/set-state! owner :title (.. % -target -value))
                                  :value (:title state) :placeholder "Title"}]
              [:p.flash-message (:flash-message state)]])
           (when-let [game (:game state)]
             (let [username (get-in @auth/app-state [:user :username])]
               [:div
                [:div.button-bar
                 (when (= (get-in game [:player :user :username]) username)
                   [:button {:on-click #(start-game owner)} "Start"])
                 [:button {:on-click #(leave-game owner)} "Leave"]]
                [:h3 (:title game)]
                [:h4 "Players"]
                [:div username]])))]]))))

(om/root game-lobby app-state {:target (. js/document (getElementById "gamelobby"))})
