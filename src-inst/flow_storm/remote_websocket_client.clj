(ns flow-storm.remote-websocket-client
  (:refer-clojure :exclude [send])
  (:require [flow-storm.json-serializer :as serializer]
            [flow-storm.utils :refer [log log-error] :as utils]
            [flow-storm.runtime.events :as rt-events])
  (:import [org.java_websocket.client WebSocketClient]
           [java.net URI]
           [org.java_websocket.handshake ServerHandshake]))

(def remote-websocket-client nil)

(defn stop-remote-websocket-client []
  (when remote-websocket-client
    (.close remote-websocket-client))
  (alter-var-root #'remote-websocket-client (constantly nil)))

(defn remote-connected? []
  (boolean remote-websocket-client))

(defn send [ser-packet]
  ;; websocket library isn't clear about thread safty of send
  ;; lets synchronize just in case
  (locking remote-websocket-client
    (.send ^WebSocketClient remote-websocket-client ^String ser-packet)))

(defn send-event-to-debugger [ev-packet]
  (let [ser-packet (serializer/serialize [:event ev-packet])]
    (send ser-packet)))

(defn start-remote-websocket-client [{:keys [host port on-connected api-call-fn]
                                      :or {host "localhost"
                                           port 7722}}]
  (let [uri-str (format "ws://%s:%s/ws" host port)
        ^WebSocketClient ws-client (proxy
                                       [WebSocketClient]
                                       [(URI. uri-str)]

                                     (onOpen [^ServerHandshake handshake-data]
                                       (log (format "Connection opened to %s" uri-str))

                                       ;; send all pending events
                                       (let [pending-events (rt-events/pop-pending-events!)]
                                         (when (seq pending-events)
                                           (log "Replaying stored events")
                                           ;; HACKY: wait a little befor sending the events to be sure
                                           ;; the debugger that just connected is ready to start
                                           ;; processing events
                                           (Thread/sleep 2000)
                                           (doseq [ev pending-events]
                                             (send-event-to-debugger ev))))

                                       (when on-connected (on-connected)))

                                     (onMessage [^String message]
                                       (let [[packet-key :as in-packet] (serializer/deserialize message)
                                             ret-packet (case packet-key
                                                          :api-request (let [[_ request-id method args] in-packet]
                                                                         (try
                                                                           (let [ret-data (api-call-fn method args)]
                                                                             [:api-response [request-id nil ret-data]])
                                                                           (catch Exception e
                                                                             [:api-response [request-id (.getMessage e) nil]])))
                                                          (log-error "Unrecognized packet key"))
                                             ret-packet-ser (serializer/serialize ret-packet)]

                                         (.send remote-websocket-client ret-packet-ser)))

                                     (onClose [code reason remote?]
                                       (log (format "Connection with %s closed. code=%s reson=%s remote?=%s"
                                                    uri-str code reason remote?)))

                                     (onError [^Exception e]
                                       (log-error (format "WebSocket error connection %s" uri-str) e)))]
    (.setConnectionLostTimeout ws-client 0)
    (.connect ws-client)

    (alter-var-root #'remote-websocket-client (constantly ws-client))
    ws-client))
