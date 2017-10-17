(defproject shrimp "0.1.0"
  :description "A ClojureScript library targeting Node.js and providing async channels on top of Red Lobster promise library."
  :url "https://github.com/pepzer/shrimp"
  :license {:name "Mozilla Public License Version 2.0"
            :url "http://mozilla.org/MPL/2.0/"}

  :min-lein-version "2.7.1"

  :dependencies [[org.clojure/clojure "1.9.0-beta1"]
                 [org.clojure/clojurescript "1.9.946"]
                 [org.clojars.pepzer/redlobster "0.2.2"]]

  :plugins [[lein-figwheel "0.5.13"]
            [lein-cljsbuild "1.1.7" :exclusions [[org.clojure/clojure]]]]

  :clean-targets ^{:protect false} ["target"]

  :source-paths ["src/clj" "src/cljs" "test/cljs"]

  :cljsbuild {
              :builds [{:id "dev"
                        :source-paths ["src/clj" "src/cljs"]
                        :figwheel true
                        :compiler {:main shrimp.dev
                                   :output-to "target/out/shrimp.js"
                                   :output-dir "target/out"
                                   :target :nodejs
                                   :optimizations :none
                                   :source-map true }}
                       {:id "test-all"
                        :source-paths ["src/clj" "src/cljs" "test/cljs"]
                        :compiler {:main shrimp.tests
                                   :output-to "target/out-test/shrimp.js"
                                   :output-dir "target/out-test"
                                   :target :nodejs
                                   :optimizations :none
                                   :source-map true }}
                       {:id "prod"
                        :source-paths ["src/clj" "src/cljs"]
                        :compiler {:output-to "target/out-rel/shrimp.js"
                                   :output-dir "target/out-rel"
                                   :target :nodejs
                                   :optimizations :advanced
                                   :source-map false }}]}

  :profiles {:dev {:source-paths ["dev"]}}
  :figwheel {})

