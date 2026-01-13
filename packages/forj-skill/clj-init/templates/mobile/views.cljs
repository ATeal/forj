(ns {{namespace}}.views
  (:require [re-frame.core :as rf]
            ["react-native" :as rn]))

(def styles
  {:container {:flex 1
               :justifyContent "center"
               :alignItems "center"
               :backgroundColor "#fff"}
   :title {:fontSize 24
           :fontWeight "bold"
           :marginBottom 20}
   :counter {:flexDirection "row"
             :alignItems "center"
             :gap 20}
   :count {:fontSize 32}
   :button {:backgroundColor "#007AFF"
            :paddingHorizontal 20
            :paddingVertical 10
            :borderRadius 8}
   :buttonText {:color "#fff"
                :fontSize 18}})

(defn counter []
  (let [count @(rf/subscribe [:app/count])]
    [:> rn/View {:style (:counter styles)}
     [:> rn/TouchableOpacity
      {:style (:button styles)
       :onPress #(rf/dispatch [:app/decrement])}
      [:> rn/Text {:style (:buttonText styles)} "-"]]
     [:> rn/Text {:style (:count styles)} (str count)]
     [:> rn/TouchableOpacity
      {:style (:button styles)
       :onPress #(rf/dispatch [:app/increment])}
      [:> rn/Text {:style (:buttonText styles)} "+"]]]))

(defn app []
  [:> rn/View {:style (:container styles)}
   [:> rn/Text {:style (:title styles)} "{{project-name}}"]
   [counter]])
