(ns {{namespace}}.events
  (:require [re-frame.core :as rf]))

(rf/reg-event-db
 :app/initialize
 (fn [_ _]
   {:count 0}))

(rf/reg-event-db
 :app/increment
 (fn [db _]
   (update db :count inc)))

(rf/reg-event-db
 :app/decrement
 (fn [db _]
   (update db :count dec)))
