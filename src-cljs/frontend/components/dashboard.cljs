(ns frontend.components.dashboard
  (:require [frontend.async :refer [raise!]]
            [frontend.components.builds-table :as builds-table]
            [frontend.components.pieces.icon :as icon]
            [frontend.components.pieces.spinner :refer [spinner]]
            [frontend.components.project.common :as project-common]
            [frontend.components.nux-bootstrap :as nux-bootstrap]
            [frontend.data.builds :as test-data]
            [frontend.models.plan :as plan-model]
            [frontend.models.user :as user]
            [frontend.routes :as routes]
            [frontend.state :as state]
            [frontend.utils :refer-macros [html]]
            [om.core :as om :include-macros true]
            [frontend.experimental.non-code-empty-state :as non-code-empty-state]))

(defn- non-code-identity-empty-dashboard []
  (om/build non-code-empty-state/empty-state-main-page
    {:name "Builds"
     :icon (icon/builds)
     :subheading "A list of your software builds with corresponding status for monitoring all of the fixes and failures you care about."
     :demo-heading "Demos"
     :demo-description "The following items are listed for demonstration. Click the link in the second column to see details of the code commit that triggered the demo build, a test summary, a debugging shell, links to artifacts, the build configuration, and build timing across your containers."
     :content (om/build builds-table/builds-table
                        {:builds (test-data/builds)}
                        {:opts {:show-actions? true
                                :show-branch? true
                                :show-project? true}})}))

(defn dashboard [data owner]
  (reify
    om/IDisplayName (display-name [_] "Dashboard")
    om/IRender
    (render [_]
      (let [builds (get-in data state/recent-builds-path)
            workflow (get-in data state/workflow-path)
            projects (get-in data state/projects-path)
            current-project (get-in data state/project-data-path)
            plan (:plan current-project)
            project (:project current-project)
            nav-data (:navigation-data data)
            page (js/parseInt (get-in nav-data [:query-params :page] 0))
            builds-per-page (:builds-per-page data)
            current-user (:current-user data)
            vcs-types [:github :bitbucket]]
        (html
          ;; ensure the both projects and builds are loaded before rendering to prevent
          ;; the build list and branch picker from resizing.
          (cond (or (nil? builds)
                    ;; if the user isn't logged in, but is viewing an oss build,
                    ;; then we will not load any projects.
                    (and current-user (nil? projects)))
                [:div.empty-placeholder (spinner)]

                (and (empty? builds)
                     projects
                     (empty? projects))

                (if (not (user/has-code-identity? current-user))
                  (non-code-identity-empty-dashboard)
                  (om/build nux-bootstrap/build-empty-state
                            {:projects (get-in data state/repos-building-path)
                             :projects-loaded? (and (get-in data (state/all-repos-loaded-path :github))
                                                    (get-in data (state/all-repos-loaded-path :bitbucket)))
                             :current-user current-user
                             :organizations (get-in data state/user-organizations-path)}))

                :else
                [:div.dashboard
                 (when (project-common/show-trial-notice? project plan (get-in data state/dismissed-trial-update-banner))
                   [:div.container-fluid
                    [:div.row
                     [:div.col-xs-12
                      (om/build project-common/trial-notice current-project)]]])

                 (when (plan-model/suspended? plan)
                   (om/build project-common/suspended-notice {:plan plan
                                                              :vcs_type (:vcs_type project)}))
                 (om/build builds-table/builds-table
                           {:builds builds
                            :projects projects
                            :workflow workflow}
                           {:opts {:show-actions? true
                                   :show-branch? (not (:branch nav-data))
                                   :show-project? (not (:repo nav-data))}})
                 [:div.recent-builds-pager
                  [:a
                   {:href (routes/v1-dashboard-path (assoc nav-data :page (max 0 (dec page))))
                    ;; no newer builds if you're on the first page
                    :class (when (zero? page) "disabled")}
                   [:i.fa.fa-long-arrow-left.newer]
                   [:span "Newer builds"]]
                  [:a
                   {:href (routes/v1-dashboard-path (assoc nav-data :page (inc page)))
                    ;; no older builds if you have less builds on the page than an
                    ;; API call returns
                    :class (when (> builds-per-page (count builds)) "disabled")}
                   [:span "Older builds"]
                   [:i.fa.fa-long-arrow-right.older]]]]))))))
