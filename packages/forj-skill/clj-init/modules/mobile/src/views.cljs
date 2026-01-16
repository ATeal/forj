(ns mobile.views
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            ["react-native" :as rn]))

(def styles
  {:container {:flex 1
               :justifyContent "center"
               :alignItems "center"
               :backgroundColor "#f5f5f5"}
   :title {:fontSize 24
           :fontWeight "bold"
           :marginBottom 20}
   :counter {:fontSize 48
             :marginBottom 20}
   :button {:backgroundColor "#007AFF"
            :paddingHorizontal 20
            :paddingVertical 10
            :borderRadius 8}
   :buttonText {:color "white"
                :fontSize 18}})

(defn app []
  (let [counter @(rf/subscribe [:counter])]
    [:> rn/View {:style (:container styles)}
     [:> rn/Text {:style (:title styles)}
      "{{project-name}}"]
     [:> rn/Text {:style (:counter styles)}
      (str counter)]
     [:> rn/TouchableOpacity
      {:style (:button styles)
       :onPress #(rf/dispatch [:increment])}
      [:> rn/Text {:style (:buttonText styles)}
       "Increment"]]]))

(comment
  ;; REPL exploration
  @(rf/subscribe [:counter])
  )
