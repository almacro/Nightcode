(ns nightcode.builders
  (:require [clojure.java.io :as io]
            [nightcode.editors :as editors]
            [nightcode.lein :as lein]
            [nightcode.shortcuts :as shortcuts]
            [nightcode.ui :as ui]
            [nightcode.utils :as utils]
            [seesaw.chooser :as chooser]
            [seesaw.color :as color]
            [seesaw.core :as s]))

(declare show-builder!)

; keep track of open builders

(def builders (atom {}))

(defn get-builder
  [path]
  (when (contains? @builders path)
    (->> [:#build-console]
         (s/select (get-in @builders [path :view]))
         first)))

; actions for builder buttons

(defn set-android-sdk!
  [& _]
  (when-let [dir (chooser/choose-file :dir (utils/read-pref :android-sdk)
                                      :selection-mode :dirs-only
                                      :remember-directory? false)]
    (utils/write-pref! :android-sdk (.getCanonicalPath dir))
    (show-builder! @ui/tree-selection)))

(defn set-robovm!
  [& _]
  (when-let [dir (chooser/choose-file :dir (utils/read-pref :robovm)
                                      :selection-mode :dirs-only
                                      :remember-directory? false)]
    (utils/write-pref! :robovm (.getCanonicalPath dir))
    (show-builder! @ui/tree-selection)))

(defn eval-in-repl!
  [console path timestamp]
  (let [source-paths (-> (lein/read-project-clj path)
                         (lein/stale-clojure-sources timestamp))
        commands (map #(format "(load-file \"%s\")" %) source-paths)
        names (map #(.getName (io/file %)) source-paths)]
    (->> (format "(do %s \"%s\")"
                 (clojure.string/join " " commands)
                 (clojure.string/join ", " names))
         (.enterLine console)
         (binding [*read-eval* false]))))

; button toggling functions

(defn toggle-visible!
  [{:keys [view]} path]
  (let [is-android-project? (lein/is-android-project? path)
        is-ios-project? (lein/is-ios-project? path)
        is-java-project? (lein/is-java-project? path)
        is-clojurescript-project? (lein/is-clojurescript-project? path)
        is-project-clj? (-> @ui/tree-selection
                            io/file
                            .getName
                            (= "project.clj"))
        buttons {:#run-repl (and (not is-ios-project?)
                                 (not is-java-project?))
                 :#reload (and (not is-ios-project?)
                               (not (and is-java-project? is-android-project?)))
                 :#test (not is-java-project?)
                 :#sdk is-android-project?
                 :#robovm is-ios-project?
                 :#auto is-clojurescript-project?
                 :#check-versions is-project-clj?}]
    (doseq [[id should-show?] buttons]
      (ui/config! view id :visible? should-show?))))

(defn toggle-color!
  [{:keys [view]} path]
  (let [project-map (lein/read-project-clj path)
        sdk (get-in project-map [:android :sdk-path])
        robovm (get-in project-map [:ios :robovm-path])
        buttons {:#sdk (and sdk (.exists (io/file sdk)))
                 :#robovm (and robovm (.exists (io/file robovm)))}]
    (doseq [[id is-set?] buttons]
      (ui/config! view id :background (when-not is-set? (color/color :red))))))

(defn toggle-enable!
  [{:keys [view process last-reload]} path]
  (let [is-java-project? (lein/is-java-project? path)
        is-running? (not (nil? @process))
        buttons {:#run (not is-running?)
                 :#run-repl (not is-running?)
                 :#reload (not (nil? @last-reload))
                 :#build (not is-running?)
                 :#test (not is-running?)
                 :#clean (not is-running?)
                 :#stop is-running?
                 :#check-versions (not is-running?)}]
    (doseq [[id should-enable?] buttons]
      (ui/config! view id :enabled? should-enable?))))

; create and show/hide builders for each project

(def ^:dynamic *builder-widgets* [:run :run-repl :reload :build :test
                                  :clean :check-versions :stop :auto])

(defn create-action
  [k path [console build-group] [process auto-process last-reload]]
  (case k
    :run (fn [& _]
           (lein/run-project! process (ui/get-io! console) path)
           (when (lein/is-java-project? path)
             (reset! last-reload (System/currentTimeMillis))))
    :run-repl (fn [& _]
                (lein/run-repl-project! process (ui/get-io! console) path)
                (when (not (lein/is-java-project? path))
                  (reset! last-reload (System/currentTimeMillis))))
    :reload (fn [& _]
              (if (lein/is-java-project? path)
                (lein/run-hot-swap! (ui/get-io! console) path)
                (eval-in-repl! console path @last-reload))
              (reset! last-reload (System/currentTimeMillis)))
    :build (fn [& _]
             (lein/build-project! process (ui/get-io! console) path))
    :test (fn [& _]
            (lein/test-project! process (ui/get-io! console) path))
    :clean (fn [& _]
             (lein/clean-project! process (ui/get-io! console) path))
    :check-versions (fn [& _]
                      (lein/check-versions-in-project!
                        process (ui/get-io! console) path))
    :stop (fn [& _]
            (lein/stop-process! process))
    :sdk set-android-sdk!
    :robovm set-robovm!
    :auto (fn [& _]
            (ui/config! build-group :#auto :selected? (nil? @auto-process))
            (if (nil? @auto-process)
              (lein/cljsbuild-project!
                auto-process (ui/get-io! console) path)
              (lein/stop-process! auto-process)))
    nil))

(defn create-widget
  [k action-fn!]
  (case k
    :run (ui/button :id k
                    :text (utils/get-string :run)
                    :listen [:action action-fn!]
                    :focusable? false)
    :run-repl (ui/button :id k
                         :text (utils/get-string :run_with_repl)
                         :listen [:action action-fn!]
                         :focusable? false)
    :reload (ui/button :id k
                       :text (utils/get-string :reload)
                       :listen [:action action-fn!]
                       :focusable? false)
    :build (ui/button :id k
                      :text (utils/get-string :build)
                      :listen [:action action-fn!]
                      :focusable? false)
    :test (ui/button :id k
                     :text (utils/get-string :test)
                     :listen [:action action-fn!]
                     :focusable? false)
    :clean (ui/button :id k
                      :text (utils/get-string :clean)
                      :listen [:action action-fn!]
                      :focusable? false)
    :check-versions (ui/button :id k
                               :text (utils/get-string :check_versions)
                               :listen [:action action-fn!]
                               :focusable? false)
    :stop (ui/button :id k
                     :text (utils/get-string :stop)
                     :listen [:action action-fn!]
                     :focusable? false)
    :sdk (ui/button :id k
                    :text (utils/get-string :android_sdk)
                    :listen [:action action-fn!]
                    :focusable? false)
    :robovm (ui/button :id k
                       :text (utils/get-string :robovm)
                       :listen [:action action-fn!]
                       :focusable? false)
    :auto (ui/toggle :id k
                     :text (utils/get-string :auto_build)
                     :listen [:action action-fn!]
                     :focusable? false)
    (s/make-widget k)))

(defn create-builder
  [path]
  (let [; create the atoms that keep track of important values
        process (atom nil)
        auto-process (atom nil)
        last-reload (atom nil)
        ; create the console and the panel that holds it
        console (editors/create-console "clj")
        build-group (s/border-panel
                      :center (s/config! console :id :build-console))
        ; put the atoms and views in vectors to make it easier to pass them
        atoms [process auto-process last-reload]
        views [console build-group]
        ; create the buttons with their actions attached
        btn-group (ui/wrap-panel
                    :items (map (fn [k]
                                  (let [a (create-action k path views atoms)]
                                    (doto (create-widget k a)
                                      (shortcuts/create-mapping! a))))
                                *builder-widgets*))]
    ; refresh the builder when the process state changes
    (add-watch process
               :refresh-builder
               (fn [_ _ _ new-state]
                 (when (nil? new-state)
                   (reset! last-reload nil))
                 (-> @ui/tree-selection ui/get-project-path show-builder!)))
    ; add the buttons to the main panel and create shortcuts
    (doto build-group
      (s/config! :north btn-group)
      shortcuts/create-hints!)
    ; return a map describing the builder
    {:view build-group
     :close-fn! (create-action :stop path views atoms)
     :should-remove-fn #(not (utils/is-project-path? path))
     :process process
     :last-reload last-reload
     :toggle-paredit-fn! (editors/init-paredit!
                           (.getTextArea console) false true)}))

(defn show-builder!
  [path]
  (let [pane (s/select @ui/ui-root [:#builder-pane])]
    ; create new builder if necessary
    (when (and path
               (utils/is-project-path? path)
               (not (contains? @builders path)))
      (when-let [builder (create-builder path)]
        (swap! builders assoc path builder)
        (.add pane (:view builder) path)))
    ; display the correct card
    (s/show-card! pane (if (contains? @builders path) path :default-card))
    ; modify pane based on the project
    (when-let [builder (get @builders path)]
      (doto builder
        (toggle-visible! path)
        (toggle-color! path)
        (toggle-enable! path)))))

(defn remove-builders!
  [path]
  (let [pane (s/select @ui/ui-root [:#builder-pane])]
    (doseq [[builder-path {:keys [view close-fn! should-remove-fn]}] @builders]
      (when (or (utils/is-parent-path? path builder-path)
                (should-remove-fn))
        (swap! builders dissoc builder-path)
        (close-fn!)
        (.remove pane view)))))

; pane

(defn create-pane
  "Returns the pane with the builders."
  []
  (s/card-panel :id :builder-pane :items [["" :default-card]]))

; watchers

(add-watch ui/tree-selection
           :show-builder
           (fn [_ _ _ path]
             ; remove any builders that aren't valid anymore
             (remove-builders! nil)
             ; show the selected builder
             (show-builder! (ui/get-project-path path))))
(add-watch editors/font-size
           :set-builder-font-size
           (fn [_ _ _ x]
             (apply editors/set-font-sizes! x (vals @builders))))
(add-watch editors/paredit-enabled?
           :set-builder-paredit
           (fn [_ _ _ enable?]
             (apply editors/set-paredit! enable? (vals @builders))))
