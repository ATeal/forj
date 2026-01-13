(ns {{namespace}}.subs
  (:require [re-frame.core :as rf]))

(rf/reg-sub
 :app/db
 (fn [db _]
   db))

(rf/reg-sub
 :app/count
 (fn [db _]
   (:count db)))
