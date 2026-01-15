(ns {{namespace}}.core
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [{{namespace}}.views :as views]))

;; -- App initialization --

(defn ^:dev/after-load reload []
  (rf/clear-subscription-cache!))

(defn init []
  (rf/dispatch-sync [:initialize-db])
  (r/as-element [views/app]))

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
