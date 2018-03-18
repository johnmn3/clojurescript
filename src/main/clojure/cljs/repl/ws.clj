;; Copyright (c) Rich Hickey. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns cljs.repl.ws
  (:require [cljs.repl]
            [cljs.closure :as cljsc]
            [cljs.compiler :as cmp]
            [cljs.env :as env])
  (:import [java.nio ByteBuffer]
           [java.io BufferedReader IOException]
           [org.java_websocket.server WebSocketServer]))

;; state
(defonce state
  (atom {:server nil
         :clients nil
         :started nil}))
(def repl-out (atom nil))
(def response (atom nil))

;; utils
(defn wait-for-client [] @(:started @state))

(defn send!
  ([msg]
   (if-let [ws (-> (:clients @state) first :ws)]
     (send! ws msg)))
  ([client msg]
   (.send client (pr-str msg))))

(defn send-for-eval! [js]
  (send! {:op :eval-js, :code js}))

;; impl
(defn ws-server-impl [host port open error close str-msg bb-msg start]
  (proxy [WebSocketServer] [(java.net.InetSocketAddress. host port #_ (read-string port))]
    (onOpen [client client-handshake]
      (open {:client client :client-handshake client-handshake}))
    (onClose [client code reason remote]
      (close {:client client :code code :reason reason :remote remote})
      (.close client))
    (onMessage [client msg]
      (condp instance? msg
        String (str-msg {:client client :msg msg})
        ByteBuffer (bb-msg {:client client :msg msg})))
    (onError [client ex]
      (error {:client client :ex ex}))
    (onStart []
      (when start
        (start)))))

(defn server [host port & args]
  (let [{:keys [open error close str-msg bb-msg start]
         :or {close (fn [{:keys [client]}]
                      (swap! state update :clients #(remove #{client} %)))
              open (fn [{:keys [client]}]
                     (-> (swap! state update :clients conj
                            {:ws client :id (gensym "client")})
                      :started (deliver true)))
              str-msg (fn [{:keys [msg]}] (println "from client:" msg))
              bb-msg str-msg
              error (fn [{:keys [client ex]}] (println client "sent error:" ex))}}

        (apply hash-map args)
        ws (ws-server-impl host port open error close str-msg bb-msg start)]
    (future (.run ws))
    ws))

;; star/stop
(defn start
  [f & {:keys [ip port]}]
  {:pre [(ifn? f)]}
  (swap! state
    assoc :server (server ip port :str-msg f)
          :clients #{}
          :started (promise)))

(defn stop []
  (let [stop-server (:server @state)]
    (when-not (nil? stop-server)
      (.stop stop-server)
      (reset! state {:server nil
                     :clients nil
                     :started nil})
      @state)))

#_
(declare send-for-eval! websocket-setup-env websocket-eval load-javascript
  websocket-tear-down-env transitive-deps)

(defmulti ws-msg
 "Process msgs from client"
 {:arglists '([_ msg])}
 (fn [_ msg] (:op msg)))

(defmethod ws-msg
  :result
  [_ msg]
  (let [result (:value msg)]
    (when-not (nil? @response)
      (deliver @response result))))

(defmethod ws-msg
  :print
  [_ msg]
  (let [string (:value msg)]
    (binding [*out* (or @repl-out *out*)]
      (print (read-string string)))))

(defmethod ws-msg
  :ready
  [_ _]
  (when-not (nil? @response)
    (deliver @response :ready)))

;; IJavaScriptEnv implementation
(defn websocket-setup-env
  [this opts]
  (reset! repl-out *out*)
  (start (fn [data] (ws-msg this (read-string (:msg data))))
         :ip (:ip this)
         :port (:port this))
  (let [{:keys [ip pre-connect]} this]
    (println (str "Waiting for connection at "
                  "ws://" ip ":" (:port this) " ..."))
    (when pre-connect (pre-connect))
    (wait-for-client)
    nil))

(defn websocket-eval
  [js]
  (reset! response (promise))
  (send-for-eval! js)
  (let [ret @@response]
    (reset! response nil)
    ret))

(defn load-javascript
  [_ provides _]
  (websocket-eval
    (str "goog.require('" (cmp/munge (first provides)) "')")))

(defn websocket-tear-down-env
  []
  (reset! repl-out nil)
  (stop)
  (println "<< stopped server >>"))

(defrecord WebsocketEnv []
  cljs.repl/IJavaScriptEnv
  (-setup [this opts] (websocket-setup-env this opts))
  (-evaluate [_ _ _ js] (websocket-eval js))
  (-load [this ns url] (load-javascript this ns url))
  (-tear-down [_] (websocket-tear-down-env)))

(defn repl-env
  "Returns a JS environment to pass to repl or piggieback"
  [& {:as opts}]
  (merge (WebsocketEnv.)
    {:ip "127.0.0.1"
     :port 9001}
    opts))
