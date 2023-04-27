(ns flow-storm.storm-api
  (:require [flow-storm.api :as fs-api]
            [flow-storm.tracer :as tracer]
            [flow-storm.runtime.debuggers-api :as debuggers-api]
            [flow-storm.runtime.indexes.api :as indexes-api]
            [flow-storm.runtime.indexes.frame-index :as frame-index]
            [flow-storm.tutorials.basics :as tut-basics]))

(defn start-recorder []
  (indexes-api/start))

(defn start-debugger []
  (let [config {:skip-index-start? true}]
    (fs-api/local-connect config)))

(def jump-to-last-exception debuggers-api/jump-to-last-exception)

(def jump-to-last-expression debuggers-api/jump-to-last-expression-in-this-thread)

(defn print-flow-storm-help []
  (println "Flow Storm settings: \n")
  (println (format "  Recording : %s" (tracer/recording?)))
  (println (format "  Fn expressions limit : %d" frame-index/fn-expr-limit))
  (println)
  (println "ClojureStorm Commands: \n")
  (println "  :dbg        - Show the FlowStorm debugger UI, you can dispose it by closing the window.")
  (println "  :rec        - Start recording. All instrumented code traces will be recorded.")
  (println "  :stop       - Stop recording. Instrumented code will execute but nothing will be recorded, so no extra heap will be consumed.")
  (println "  :ex         - Focus the last recorded exception.")
  (println "  :last       - Focus the last recorded expression on this thread.")
  (println "  :help       - Print this help.")
  (println "  :tut/basics - Starts the basics tutorial.")
  (println)
  (println "JVM config properties: \n")
  (println "  -Dflowstorm.startRecording              [true|false]")
  (println "  -Dflowstorm.fnExpressionsLimit          INTEGER (defaults to 10000)")
  (println "  -Dflowstorm.theme                       [dark|light|auto] (defaults to auto)")
  (println "  -Dflowstorm.styles                      [STRING] Ex: /home/user/my-styles.css")
  (println))

(defn maybe-execute-flow-storm-specials [input]
  (case input
    :dbg        (do (start-debugger)             true)
    :ex         (do (jump-to-last-exception)     true)
    :last       (do (jump-to-last-expression)    true)
    :rec        (do (tracer/set-recording true)  true)
    :stop       (do (tracer/set-recording false) true)

    :tut/basics (do (tut-basics/start)         true)
    :tut/next   (do (tut-basics/step-next)     true)
    :tut/prev   (do (tut-basics/step-prev)     true)
    false))