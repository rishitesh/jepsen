(ns jepsen.core-test
  (:use jepsen.core
        clojure.test
        clojure.pprint
        clojure.tools.logging)
  (:require [jepsen.os :as os]
            [jepsen.db :as db]
            [jepsen.tests :as tst]
            [jepsen.control :as control]
            [jepsen.client :as client]
            [jepsen.generator :as gen]
            [jepsen.model :as model]
            [jepsen.checker :as checker]))

(deftest basic-cas-test
  (let [state (atom nil)
        db    (tst/atom-db state)
        n     10
        test  (run! (assoc tst/noop-test
                           :db         (tst/atom-db state)
                           :client     (tst/atom-client state)
                           :generator  (->> gen/cas
                                            (gen/limit n)
                                            (gen/nemesis gen/void))
                           :model      (model/->CASRegister 0)))]
    (is (:valid? (:results test)))))

(deftest ssh-test
  (let [os-startups  (atom {})
        os-teardowns (atom {})
        db-startups  (atom {})
        db-teardowns (atom {})
        db-primaries (atom [])
        test (run! (assoc tst/noop-test
                          :os (reify os/OS
                                (setup! [_ test node]
                                  (swap! os-startups assoc node
                                         (control/exec :hostname)))

                                (teardown! [_ test node]
                                  (swap! os-teardowns assoc node
                                         (control/exec :hostname))))

                          :db (reify db/DB
                                (setup! [_ test node]
                                  (swap! db-startups assoc node
                                         (control/exec :hostname)))

                                (teardown! [_ test node]
                                  (swap! db-teardowns assoc node
                                         (control/exec :hostname)))

                                db/Primary
                                (setup-primary! [_ test node]
                                  (swap! db-primaries conj
                                         (control/exec :hostname))))))]

    (is (:valid? (:results test)))
    (is (= @os-startups @os-teardowns @db-startups @db-teardowns
           {:n1 "n1"
            :n2 "n2"
            :n3 "n3"
            :n4 "n4"
            :n5 "n5"}))
    (is (= @db-primaries ["n1"]))))

(deftest worker-recovery-test
  ; Workers should only consume n ops even when failing.
  (let [invocations (atom 0)
        n 30]
    (run! (assoc tst/noop-test
                 :client (reify client/Client
                           (setup! [c _ _] c)
                           (invoke! [_ _ _]
                             (swap! invocations inc)
                             (assert false))
                           (teardown! [c _]))
                 :checker  checker/unbridled-optimism
                 :generator (->> (gen/queue)
                                 (gen/limit n)
                                 (gen/nemesis gen/void))))
      (is (= n @invocations))))
