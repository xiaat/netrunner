(ns netrunner.deckbuilder
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [om.core :as om :include-macros true]
            [sablono.core :as sab :include-macros true]
            [cljs.core.async :refer [chan put! <! timeout] :as async]
            [clojure.string :refer [join]]
            [netrunner.auth :refer [auth-channel] :as auth]
            [netrunner.cardbrowser :refer [cards-channel] :as cb]
            [netrunner.ajax :refer [POST GET]]
            [netrunner.deck :refer [parse-deck]]))

(def app-state (atom {:decks []}))

(defn fetch-decks []
  (go (let [data (:json (<! (GET (str "/data/decks"))))
            loaded (<! cards-channel)
            decks (for [deck data]
                    (let [cards (map #(str (:qty %) " " (:card %)) (:cards deck))]
                      (assoc deck :cards (parse-deck (join "\n" cards)))))]
        (swap! app-state assoc :decks decks))))

(if (:user @auth/app-state)
  (fetch-decks)
  (go (<! auth-channel)
      (fetch-decks)))

(defn side-identities [side]
  (filter #(and (= (:side %) side)
                (not (#{"Special" "Alternates"} (:setname %)))
                (= (:type %) "Identity")) (:cards @cb/app-state)))

(defn get-card [title]
  (some #(when (= (:title %) title) %) (:cards @cb/app-state)))

(defn deck->str [cards]
  (reduce #(str %1 (:qty %2) " " (get-in %2 [:card :title]) "\n") "" cards))

(defn influence [deck]
  (let [faction (get-in deck [:identity :faction] deck)
        cards (:cards deck)]
    (reduce #(let [card (:card %2)]
               (if (= (:faction card) faction)
                 %1
                 (+ %1 (* (:qty %2) (:factioncost card)))))
            0 (:cards deck))))

(defn card-count [cards]
  (reduce #(+ %1 (:qty %2)) 0 cards))

(defn min-agenda-points [deck]
  (let [size (max (card-count (:cards deck)) (get-in deck [:identity :minimumdecksize]))]
    (+ 2 (* 2 (quot size 5)))))

(defn agenda-points [cards]
  (reduce #(if-let [point (get-in %2 [:card :agendapoints])]
             (+ (* point (:qty %2)) %1) %1) 0 cards))

(defn edit-deck [owner]
  (om/set-state! owner :edit true)
  (om/set-state! owner :deck-edit (deck->str (om/get-state owner [:deck :cards])))
  (-> owner (om/get-node "viewport") js/$ (.css "left" -500))
  (go (<! (timeout 500))
      (-> owner (om/get-node "deckname") js/$ .focus)))

(defn new-deck [side owner]
  (om/set-state! owner :deck {:side side :name "New deck" :cards []
                              :identity (-> side side-identities first)})
  (edit-deck owner))

(defn end-edit [owner]
  (om/set-state! owner :edit false)
  (-> owner (om/get-node "viewport") js/$ (.css "left" 0)))

(defn save-deck [owner]
  (end-edit owner)
  (let [deck (om/get-state owner :deck)
        decks (:decks @app-state)
        cards (for [card (:cards deck)]
                {:qty (:qty card) :card (get-in card [:card :title])})
        data (assoc deck :cards cards)]
    (if-let [id (:_id deck)]
      (swap! app-state assoc :decks (map #(if (= id (:_id %)) deck %) decks))
      (swap! app-state assoc :decks (conj decks deck)))
    (go (let [response (<! (POST "/data/decks/" data :json))]))))

(defn handle-edit [owner]
  (let [text (-> owner (om/get-node "deck-edit") .-value)]
    (om/set-state! owner :deck-edit text)
    (om/set-state! owner [:deck :cards] (parse-deck text))))

(defn handle-delete [cursor owner]
  (let [deck (om/get-state owner :deck)]
    (go (let [response (<! (POST "/data/decks/delete" deck :json))]))
    (om/transact! cursor :decks (fn [ds] (remove #(= deck %) ds)))
    (om/set-state! owner :deck (first (:decks @cursor)))))

(defn deck-builder [{:keys [decks] :as cursor} owner]
  (reify
    om/IInitState
    (init-state [this]
      {:edit false
       :card-search ""
       :quantity 1
       :deck-edit ""
       :deck nil})

    om/IDidUpdate
    (did-update [this prev-props prev-state]
      (if (and (not (empty? decks)) (not (:deck prev-state)))
        (om/set-state! owner :deck (first decks))))

    om/IRenderState
    (render-state [this state]
      (sab/html
       [:div
        [:div.deckbuilder.blue-shade.panel
         [:div.viewport {:ref "viewport"}
          [:div.decks
           [:div.button-bar
            [:button {:on-click #(new-deck "Corp" owner)} "New Corp deck"]
            [:button {:on-click #(new-deck "Runner" owner)} "New Runner deck"]]
           [:div.deck-collection
            (if (empty? decks)
              [:h4 "You have no deck"]
              (for [deck (:decks cursor)]
                [:div.deckline {:class (when (= (:deck state) deck) "active")
                                :on-click #(om/set-state! owner :deck deck)}
                 [:h4 (:name deck)]
                 [:p (get-in deck [:identity :title])]]))]]

          [:div.decklist
           (when-let [deck (:deck state)]
             (let [identity (:identity deck)
                   cards (:cards deck)]
               [:div
                (if (:edit state)
                  [:span
                   [:button {:on-click #(end-edit owner)} "Cancel"]
                   [:button {:on-click #(save-deck owner)} "Save"]]
                  [:span
                   [:button {:on-click #(handle-delete cursor owner)} "Delete"]
                   [:button {:on-click #(edit-deck owner)} "Edit"]])
                [:h3 (:name deck)]
                [:h4 (:title identity)]
                [:div
                 (let [count (card-count cards)
                       min-count (:minimumdecksize identity)]
                   [:div count " cards"
                    (when (< count min-count)
                      [:span.invalid (str "(minimum " min-count ")")])])]
                (let [inf (influence deck)
                      limit (:influencelimit identity)]
                  [:div "Influence: "
                   [:span {:class (when (> inf limit) "invalid")} inf]
                   "/" (:influencelimit identity)])
                (when (= (:side identity) "Corp")
                  (let [min-point (min-agenda-points deck)
                        points (agenda-points cards)]
                    [:div "Agenda points: " points
                     (when (< points min-point)
                       [:span.invalid "(minimum " min-point ")"])
                     (when (> points (inc min-point))
                       [:span.invalid "(maximum" (inc min-point) ")"])]))
                [:div.cards
                 (for [group (group-by #(get-in % [:card :type]) cards)]
                   [:div.group
                    [:h4 (str (or (first group) "Unknown") " (" (card-count (last group)) ")") ]
                    (for [line (last group)]
                      [:div.line (:qty line) " "
                       (if-let [name (get-in line [:card :title])]
                         (let [card (:card line)]
                           [:span
                            [:a {:href ""} name]
                            (when-not (or (= (:faction card) (:faction identity))
                                          (zero? (:factioncost card)))
                              (let [influence (* (:factioncost card) (:qty line))]
                                [:span.influence
                                 {:class (-> card :faction .toLowerCase (.replace " " "-"))
                                  :dangerouslySetInnerHTML
                                  #js {:__html (apply str (for [i (range influence)] "&#8226;"))}}]))])
                         (:card line))])])]]))]

          [:div.deckedit
           [:div
            [:p
             [:h4 "Deck name"]
             [:input.deckname {:type "text" :placeholder "Deck name"
                               :ref "deckname" :value (get-in state [:deck :name])
                               :on-change #(om/set-state! owner [:deck :name] (.. % -target -value))}]]
            [:p
             [:h4 "Identity"]
             [:select.identity {:value (get-in state [:deck :identity :title])
                                :on-change #(om/set-state! owner [:deck :identity] (get-card (.. % -target -value)))}
              (for [card (side-identities (get-in state [:deck :side]))]
                [:option (:title card)])]]
            [:p
             [:h4 "Card lookup"]
             [:form
              [:input.lookup {:type "text" :placeholder "Card" :value (:card-search state)}] " x "
              [:input.qty {:type "text" :value (:quantity state)}]
              [:button {:on-click #()} "Add to deck"]]]
            [:h4 "Decklist"]
            [:textarea {:ref "deck-edit" :value (:deck-edit state)
                        :placeholder "Copy & paste a decklist. Or start typing."
                        :on-change #(handle-edit owner)}]]]]]]))))

(om/root deck-builder app-state {:target (. js/document (getElementById "deckbuilder"))})
