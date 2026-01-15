(ns {{project-name}}.core
  (:require [reagent.dom :as rdom]
            [re-frame.core :as rf]
            [{{project-name}}.views :as views]))

;; -- App initialization --

(defn ^:dev/after-load reload []
  (rf/clear-subscription-cache!)
  (rdom/render [views/app]
               (.getElementById js/document "app")))

(defn init []
  (rf/dispatch-sync [:initialize-db])
  (reload))

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
