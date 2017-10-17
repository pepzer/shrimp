;; Copyright © 2017 Giuseppe Zerbo. All rights reserved.
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns shrimp.tests
  (:require [shrimp.core-test]
            [cljs.nodejs :as nodejs])
  (:use-macros [shrimp.test.macros :only [run-async-tests]]))

(nodejs/enable-util-print!)

(defn -main
  [& args]
  (run-async-tests
   shrimp.core-test))

(set! *main-cli-fn* -main)
