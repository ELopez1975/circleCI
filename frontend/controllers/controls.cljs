(ns frontend.controllers.controls
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer put! close!]]
            [clojure.string :as string]
            [dommy.core :as dommy]
            [frontend.models.project :as project-model]
            [frontend.controllers.api :as api]
            goog.dom
            goog.dom.classes
            [goog.string :as gstring]
            goog.string.format
            goog.style
            [frontend.intercom :as intercom]
            [frontend.utils.vcs-url :as vcs-url]
            [frontend.utils :as utils :refer [mlog]]
            [cljs.reader :as reader]
            [clojure.string :as string]
            [frontend.utils :as utils :refer [mlog]])
  (:require-macros [frontend.utils :refer [inspect timing]]
                   [dommy.macros :refer [node sel sel1]])
  (:import [goog.fx.dom.Scroll]))

;; --- Helper Methods ---

(defn container-id [container]
  (int (last (re-find #"container_(\d+)" (.-id container)))) 

;; --- Navigation Multimethod Declarations ---

(defmulti control-event
  ;; target is the DOM node at the top level for the app
  ;; message is the dispatch method (1st arg in the channel vector)
  ;; state is current state of the app
  ;; return value is the new state
  (fn [target message args state] message))

(defmulti post-control-event!
  (fn [target message args previous-state current-state] message))

;; --- Navigation Mutlimethod Implementations ---

(defmethod control-event :default
  [target message args state]
  (mlog "Unknown controls: " message)
  state)

(defmethod post-control-event! :default
  [target message args previous-state current-state]
  (mlog "No post-control for: " message))


(defmethod control-event :user-menu-toggled
  [target message _ state]
  (update-in state [:settings :menus :user :open] not))


(defmethod control-event :show-all-branches-toggled
  [target message project-id state]
  (update-in state [:settings :projects project-id :show-all-branches] not))

(defmethod post-control-event! :show-all-branches-toggled
  [target message project-id previous-state current-state]
  ;;; XXX This should happen on routing, obviously
  ;; (print project-id
  ;;        " show-all-branches-toggled "
  ;;        (get-in previous-state [:settings :projects project-id :show-all-branches])
  ;;        " => "
  ;;        (get-in current-state [:settings :projects project-id :show-all-branches]))
  )


(defmethod control-event :state-restored
  [target message path state]
  (let [str-data (.getItem js/localStorage "circle-state")]
    (if (seq str-data)
      (-> str-data
          reader/read-string
          (assoc :comms (:comms state)))
      state)))


(defmethod control-event :usage-queue-why-toggled
  [target message {:keys [build-id]} state]
  (update-in state [:current-build :show-usage-queue] not))

(defmethod post-control-event! :usage-queue-why-toggled
  [target message {:keys [username reponame
                          build_num build-id]} previous-state current-state]
  (when (get-in current-state [:current-build :show-usage-queue])
    (let [api-ch (get-in current-state [:comms :api])]
      (utils/ajax :get
                  (gstring/format "/api/v1/project/%s/%s/%s/usage-queue"
                                  username reponame build_num)
                  :usage-queue
                  api-ch
                  :context build-id))))


(defmethod control-event :selected-add-projects-org
  [target message args state]
  (-> state
      (assoc-in [:settings :add-projects :selected-org] args)
      (assoc-in [:settings :add-projects :repo-filter-string] "")))

(defmethod post-control-event! :selected-add-projects-org
  [target message args previous-state current-state]
  (let [login (:login args)
        type (:type args)
        api-ch (get-in current-state [:comms :api])]
    (utils/ajax :get
              (gstring/format "/api/v1/user/%s/%s/repos" (name type) login)
              :repos
              api-ch
              :context args)))


(defmethod control-event :show-artifacts-toggled
  [target message build-id state]
  (update-in state [:current-build :show-artifacts] not))

(defmethod post-control-event! :show-artifacts-toggled
  [target message {:keys [username reponame
                          build_num build-id]} previous-state current-state]
  (when (get-in current-state [:current-build :show-artifacts])
    (let [api-ch (get-in current-state [:comms :api])]
      (utils/ajax :get
                  (gstring/format "/api/v1/project/%s/%s/%s/artifacts"
                                  username reponame build_num)
                  :build-artifacts
                  api-ch
                  :context build-id))))


(defmethod control-event :container-selected
  [target message container-id state]
  (assoc-in state [:current-build :current-container-id] container-id))

(defmethod post-control-event! :container-selected
  [target message container-id previous-state current-state]
  (when-let [parent (sel1 target "#container_parent")]
    (let [container (sel1 target (str "#container_" container-id))
          current-scroll-top (.-scrollTop parent)
          current-scroll-left (.-scrollLeft parent)
          new-scroll-left (int (.-x (goog.style.getContainerOffsetToScrollInto container parent)))
          scroller (or (.-scroll_handler parent)
                       (set! (.-scroll_handler parent)
                             ;; Store this on the parent so that we don't handle parent scroll while
                             ;; the animation is playing
                             (goog.fx.dom.Scroll. parent
                                                  #js [0 0]
                                                  #js [0 0]
                                                  250)))]
      (set! (.-startPoint scroller) #js [current-scroll-left current-scroll-top])
      (set! (.-endPoint scroller) #js [new-scroll-left current-scroll-top])
      (.play scroller))))


(defmethod control-event :action-log-output-toggled
  [target message {:keys [index step]} state]
  (update-in state [:current-build :containers index :actions step :show-output] not))

(defmethod post-control-event! :action-log-output-toggled
  [target message {:keys [index step] :as args} previous-state current-state]
  (let [action (get-in current-state [:current-build :containers index :actions step])]
    (when (and (:show-output action)
               (:has_output action)
               (not (:output action)))
      (let [api-ch (get-in current-state [:comms :api])

            url (if (:output_url action)
                  (:output_url action)
                  (gstring/format "/api/v1/project/%s/%s/output/%s/%s"
                                  (vcs-url/project-name (get-in current-state [:current-build :vcs_url]))
                                  (get-in current-state [:current-build :build_num])
                                  step
                                  index))]
        (utils/ajax :get
                    url
                    :action-log
                    api-ch
                    :context args)))))


(defmethod control-event :selected-project-parallelism
  [target message {:keys [project-id parallelism]} state]
  (assoc-in state [:current-project :parallel] parallelism))

(defmethod post-control-event! :selected-project-parallelism
  [target message {:keys [project-id parallelism]} previous-state current-state]
  (when (not= (get-in previous-state [:current-project :parallel])
              (get-in current-state [:current-project :parallel]))
    (let [project-name (vcs-url/project-name project-id)
          api-ch (get-in current-state [:comms :api])]
      ;; TODO: edit project settings api call should respond with updated project settings
      (utils/ajax :put
                  (gstring/format "/api/v1/project/%s/settings" project-name)
                  :update-project-parallelism
                  api-ch
                  :params {:parallel parallelism}
                  :context {:project-id project-id}))))


(defmethod control-event :edited-input
  [target message {:keys [value path]} state]
  (assoc-in state path value))


(defmethod control-event :toggled-input
  [target message {:keys [path]} state]
  (update-in state path not))


(defmethod post-control-event! :intercom-dialog-raised
  [target message dialog-message previous-state current-state]
  (intercom/raise-dialog (get-in current-state [:comms :errors]) dialog-message))


(defmethod post-control-event! :intercom-user-inspected
  [target message criteria previous-state current-state]
  (if-let [url (intercom/user-link)]
    (js/window.open url)
    (print "No matching url could be found from current window.location.pathname")))


(defmethod post-control-event! :state-persisted
  [target message channel-id previous-state current-state]
  (.setItem js/localStorage "circle-state"
            (pr-str (dissoc current-state :comms))))


(defmethod post-control-event! :retry-build-clicked
  [target message {:keys [build-num build-id vcs-url] :as args} previous-state current-state]
  (let [api-ch (-> current-state :comms :api)
        org-name (vcs-url/org-name vcs-url)
        repo-name (vcs-url/repo-name vcs-url)]
    (utils/ajax :post
                (gstring/format "/api/v1/project/%s/%s/%s/retry" org-name repo-name build-num)
                :retry-build
                api-ch)))


(defmethod post-control-event! :followed-repo
  [target message repo previous-state current-state]
  (let [api-ch (get-in current-state [:comms :api])]
    (utils/ajax :post
                (gstring/format "/api/v1/project/%s/follow" (vcs-url/project-name (:vcs_url repo)))
                :followed-repo
                api-ch
                :context repo)))


;; XXX: clean this up
(defmethod post-control-event! :container-parent-scroll
  [target message _ previous-state current-state]
  (let [controls-ch (get-in current-state [:comms :controls])
        current-container-id (get-in current-state [:current-build :current-container-id] 0)
        parent (sel1 target "#container_parent")
        parent-scroll-left (.-scrollLeft parent)
        current-container (sel1 target (str "#container_" current-container-id))
        current-container-scroll-left (int (.-x (goog.style.getContainerOffsetToScrollInto current-container parent)))
        parent-scroll-left (.-scrollLeft parent)
        ;; XXX stop making (count containers) queries on each scroll
        containers (sort-by (fn [c] (Math/abs (- parent-scroll-left (.-x (goog.style.getContainerOffsetToScrollInto c parent)))))
                            (sel parent ".container-view"))
        ;; if we're scrolling left, then we want the container whose rightmost portion is showing
        ;; if we're scrolling right, then we want the container whose leftmost portion is showing
        new-scrolled-container-id (if (= parent-scroll-left current-container-scroll-left)
                                    current-container-id
                                    (if (< parent-scroll-left current-container-scroll-left)
                                      (apply min (map container-id (take 2 containers)))
                                      (apply max (map container-id (take 2 containers)))))]
    ;; This is kind of dangerous, we could end up with an infinite loop. Might want to
    ;; do a swap here (or find a better way to structure this!)
    (when (not= current-container-id new-scrolled-container-id)
      (put! controls-ch [:container-selected new-scrolled-container-id]))))


(defmethod post-control-event! :started-edit-settings-build
  [target message {:keys [project-id branch]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        api-ch (get-in current-state [:comms :api])]
    ;; TODO: edit project settings api call should respond with updated project settings
    (utils/ajax :post
                (gstring/format "/api/v1/project/%s/tree/%s" project-name (gstring/urlEncode branch))
                :start-build
                api-ch)))


(defmethod post-control-event! :created-env-var
  [target message {:keys [project-id env-var]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        api-ch (get-in current-state [:comms :api])]
    (utils/ajax :post
                (gstring/format "/api/v1/project/%s/envvar" project-name)
                :create-env-var
                api-ch
                :params env-var
                :context {:project-id project-id})))


(defmethod post-control-event! :deleted-env-var
  [target message {:keys [project-id env-var-name]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        api-ch (get-in current-state [:comms :api])]
    (utils/ajax :delete
                (gstring/format "/api/v1/project/%s/envvar/%s" project-name env-var-name)
                :delete-env-var
                api-ch
                :context {:project-id project-id
                          :env-var-name env-var-name})))


(defmethod post-control-event! :saved-dependencies-commands
  [target message {:keys [project-id settings]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        api-ch (get-in current-state [:comms :api])]
    (utils/ajax :put
                (gstring/format "/api/v1/project/%s/settings" project-name)
                :save-dependencies-commands
                api-ch
                :params settings
                :context {:project-id project-id})))


(defmethod post-control-event! :saved-test-commands
  [target message {:keys [project-id settings]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        api-ch (get-in current-state [:comms :api])]
    (utils/ajax :put
                (gstring/format "/api/v1/project/%s/settings" project-name)
                :save-test-commands
                api-ch
                :params settings
                :context {:project-id project-id})))


(defmethod post-control-event! :saved-test-commands-and-build
  [target message {:keys [project-id settings branch]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        api-ch (get-in current-state [:comms :api])]
    (utils/ajax :put
                (gstring/format "/api/v1/project/%s/settings" project-name)
                :save-test-commands-and-build
                api-ch
                :params settings
                :context {:project-id project-id
                          :branch branch})))


(defmethod post-control-event! :saved-notification-hooks
  [target message {:keys [project-id]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        api-ch (get-in current-state [:comms :api])
        settings (project-model/notification-settings (:current-project current-state))]
    (utils/ajax :put
                (gstring/format "/api/v1/project/%s/settings" project-name)
                :save-notification-hooks
                api-ch
                :params settings
                :context {:project-id project-id})))


(defmethod post-control-event! :saved-ssh-key
  [target message {:keys [project-id ssh-key]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        api-ch (get-in current-state [:comms :api])]
    (utils/ajax :post
                (gstring/format "/api/v1/project/%s/ssh-key" project-name)
                :save-ssh-key
                api-ch
                :params ssh-key
                :context {:project-id project-id})))


(defmethod post-control-event! :deleted-ssh-key
  [target message {:keys [project-id fingerprint]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        api-ch (get-in current-state [:comms :api])]
    (utils/ajax :delete
                (gstring/format "/api/v1/project/%s/ssh-key" project-name)
                :delete-ssh-key
                api-ch
                :params {:fingerprint fingerprint}
                :context {:project-id project-id
                          :fingerprint fingerprint})))


(defmethod post-control-event! :saved-project-api-token
  [target message {:keys [project-id api-token]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        api-ch (get-in current-state [:comms :api])]
    (utils/ajax :post
                (gstring/format "/api/v1/project/%s/token" project-name)
                :save-project-api-token
                api-ch
                :params api-token
                :context {:project-id project-id})))


(defmethod post-control-event! :deleted-project-api-token
  [target message {:keys [project-id token]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        api-ch (get-in current-state [:comms :api])]
    (utils/ajax :delete
                (gstring/format "/api/v1/project/%s/token/%s" project-name token)
                :delete-project-api-token
                api-ch
                :context {:project-id project-id
                          :token token})))


(defmethod post-control-event! :set-heroku-deploy-user
  [target message {:keys [project-id login]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        api-ch (get-in current-state [:comms :api])]
    (utils/ajax :post
                (gstring/format "/api/v1/project/%s/heroku-deploy-user" project-name)
                :set-heroku-deploy-user
                api-ch
                :context {:project-id project-id
                          :login login})))


(defmethod post-control-event! :removed-heroku-deploy-user
  [target message {:keys [project-id]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        api-ch (get-in current-state [:comms :api])]
    (utils/ajax :delete
                (gstring/format "/api/v1/project/%s/heroku-deploy-user" project-name)
                :remove-heroku-deploy-user
                api-ch
                :context {:project-id project-id})))


(defmethod post-control-event! :set-user-session-setting
  [target message {:keys [setting value]} previous-state current-state]
  (set! (.. js/window -location -search) (str "?" (name setting) "=" value)))
