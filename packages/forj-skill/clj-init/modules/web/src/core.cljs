(ns web.core
  (:require [reagent.dom.client :as rdom]
            [re-frame.core :as rf]
            [web.views :as views]))

;; -- App initialization (React 18+) --

(defonce root (atom nil))

(defn ^:dev/after-load reload []
  (rf/clear-subscription-cache!)
  (when @root
    (rdom/render @root [views/app])))

(defn init []
  (rf/dispatch-sync [:initialize-db])
  (reset! root (rdom/create-root (.getElementById js/document "app")))
  (rdom/render @root [views/app]))

;; -- Re-frame setup --

(rf/reg-event-db
 :initialize-db
 (fn [_ _]
   {:counter 0}))

(rf/reg-sub
 :counter
 (fn [db _]
   (:counter db)))

(rf/reg-event-db
 :increment
 (fn [db _]
   (update db :counter inc)))

(comment
  ;; REPL exploration
  @(rf/subscribe [:counter])
  (rf/dispatch [:increment])
  )
