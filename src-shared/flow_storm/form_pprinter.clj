(ns flow-storm.form-pprinter
  (:require [clojure.pprint :as pp]
            [flow-storm.utils :as utils]
            [hansel.utils :as hansel-utils])
  (:import [java.util ArrayDeque]))

(defn- seq-delims

  "Given a seq? map? or set? form returns a vector
  with the open and closing delimiters."

  [form]
  (let [delims (pr-str (empty form))]
    (if (= (count delims) 2)
      [(str (first delims)) (str (second delims))]
      ["#{" "}"])))

(defn- form-tokens

  "Given a form returns a collection of tokens.
  If the form contains ::coord meta it will be attached to the
  tokens.
  Eg for the form (with-meta '(+ 1 2) {::coord [1]}) it will return :

  [{:coord [1], :kind :text, :text \"(\"}
   {:kind :text, :text \"+\"}
   {:kind :text, :text \"1\"}
   {:kind :text, :text \"2\"}
   {:coord [1], :kind :text, :text \")\"}]"

  [form]
  (let [curr-coord (::coord (meta form))
        tok (if curr-coord
              {:coord curr-coord}
              {})]
    (cond
      (or (seq? form) (vector? form) (set? form))
      (let [[db de] (seq-delims form)]
        (-> [(assoc tok
                    :kind :text
                    :text db)]
            (into (mapcat (fn [f] (form-tokens f)) form))
            (into [(assoc tok
                          :kind :text
                          :text de)])))

      (map? form)
      (let [keys-vals (mapcat identity form)
            keys-vals-tokens (mapcat (fn [f] (form-tokens f))
                                     keys-vals)]
        (-> [(assoc tok
                    :kind :text
                    :text "{")]
            (into keys-vals-tokens)
            (into [(assoc tok
                          :kind :text
                          :text "}")])))

      :else
      [(assoc tok
              :kind :text
              :text (pr-str form))])))

(defn- consecutive-layout-tokens

  "Given a map of {positions -> tokens} and a idx
  return a vector of all the consecutive tokens by incrementing
  idx. Will stop at the first gap.   "

  [pos->layout-token idx]
  (loop [i (inc idx)
         layout-tokens [(pos->layout-token idx)]]
    (if-let [ltok (pos->layout-token i)]
      (recur (inc i) (conj layout-tokens ltok))
      layout-tokens)))

;; This is a fix for https://ask.clojure.org/index.php/13455/clojure-pprint-pprint-bug-when-using-the-code-dispatch-table
(defn- pprint-let [alis]
  (let [base-sym (first alis)]
    (if (and (next alis) (vector? (second alis)))
      (pp/pprint-logical-block
       :prefix "(" :suffix ")"
       (do
         ((pp/formatter-out "~w ~1I~@_") base-sym)
         (#'pp/pprint-binding-form (second alis))
         ((pp/formatter-out " ~_~{~w~^ ~_~}") (next (rest alis)))))
      (#'pp/pprint-simple-code-list alis))))

(def hacked-code-table
  (#'pp/two-forms
     (#'pp/add-core-ns
        {'def #'pp/pprint-hold-first, 'defonce #'pp/pprint-hold-first,
         'defn #'pp/pprint-defn, 'defn- #'pp/pprint-defn, 'defmacro #'pp/pprint-defn, 'fn #'pp/pprint-defn,
         'let #'pprint-let, 'loop #'pprint-let, 'binding #'pprint-let,
         'with-local-vars #'pprint-let, 'with-open #'pprint-let, 'when-let #'pprint-let,
         'if-let #'pprint-let, 'doseq #'pprint-let, 'dotimes #'pprint-let,
         'when-first #'pprint-let,
         'if #'pp/pprint-if, 'if-not #'pp/pprint-if, 'when #'pp/pprint-if, 'when-not #'pp/pprint-if,
         'cond #'pp/pprint-cond, 'condp #'pp/pprint-condp,

         'fn* #'pp/pprint-simple-code-list, ;; <--- all for changing this from `pp/pprint-anon-func` to `pp/pprint-simple-code-list`
         ;;      so it doesn't substitute anonymous functions
         '. #'pp/pprint-hold-first, '.. #'pp/pprint-hold-first, '-> #'pp/pprint-hold-first,
         'locking #'pp/pprint-hold-first, 'struct #'pp/pprint-hold-first,
         'struct-map #'pp/pprint-hold-first, 'ns #'pp/pprint-ns
         })))

(defn code-pprint [form]
  ;; Had to hack pprint like this because code pprinting replace (fn [arg#] ... arg# ...) with #(... % ...)
  ;; and #' with var, deref with @ etc, wich breaks our pprintln system
  ;; This is super hacky! because I wasn't able to use with-redefs (it didn't work) I replace
  ;; the pprint method for ISeqs for the duration of our printing

  (#'pp/use-method pp/code-dispatch clojure.lang.ISeq (fn [alis] ;; <---- this hack disables reader macro sustitution
                                                        (if-let [special-form (hacked-code-table (first alis))]
                                                          (special-form alis)
                                                          (#'pp/pprint-simple-code-list alis))))

  (binding [pp/*print-pprint-dispatch* pp/code-dispatch
            pp/*code-table* hacked-code-table]
    (let [pprinted-form-str (utils/normalize-newlines
                             (with-out-str
                               (pp/pprint form)))]

      ;; restore the original pprint so we don't break it
      (#'pp/use-method pp/code-dispatch clojure.lang.ISeq #'pp/pprint-code-list)

      pprinted-form-str)))

(defn pprint-tokens

  "Given a form, returns a vector of tokens to pretty print it.
  Tokens can be any of :
  - {:kind :text, :text STRING, :idx-from INT, :len INT :coord COORD}
  - {:kind :sp}
  - {:kind :nl}"

  [form]
  (let [form (hansel-utils/tag-form-recursively form ::coord)
        pprinted-str (code-pprint form)
        ;; a map of positions of the form pprinted string that contains spaces or new-lines
        pos->layout-token (->> pprinted-str
                               (keep-indexed (fn [i c]
                                               (cond
                                                 (= c \newline) [i {:kind :nl}]
                                                 (= c \space)   [i {:kind :sp}]
                                                 (= c \,)       [i {:kind :sp}]
                                                 :else nil)))
                               (into {}))
        ;; all the tokens for form, whithout any newline or indentation info
        pre-tokens (form-tokens form)

        ;; interleave in pre-tokens newlines and space tokens found by the pprinter

        final-tokens (loop [[{:keys [text] :as text-tok} & next-tokens] pre-tokens
                            i 0
                            final-toks []]
                       (if-not text-tok
                         final-toks
                         (if (pos->layout-token i)
                           ;; if there are layout tokens for the current position
                           ;; insert them before the current text-tok
                           (let [consecutive-lay-toks (consecutive-layout-tokens pos->layout-token i)]
                             (recur next-tokens
                                    (+ i  (count consecutive-lay-toks) (count text))
                                    (-> final-toks
                                        (into consecutive-lay-toks)
                                        (into  [(assoc text-tok
                                                       :idx-from (+ i (count consecutive-lay-toks))
                                                       :len (count text))]))))

                           ;; else just add the text-tok
                           (recur next-tokens
                                  (+ i (count text))
                                  (into final-toks [(assoc text-tok
                                                           :idx-from i
                                                           :len (count text))])))))]
    final-tokens))

(defn to-string

  "Given ptokens as generated by `pprint-tokens` render them into a string."

  [ptokens]
  (with-out-str
    (doseq [{:keys [kind text]} ptokens]
     (case kind
       :sp   (print " ")
       :nl   (println)
       :text (print text)))))

(defn- read-contiguous-spaces [^ArrayDeque ptokens-q]
  (loop [len 0]
    (if (.isEmpty ptokens-q)
      len
      (let [{:keys [kind]} (.peek ptokens-q)]
        (if (= :sp kind)
          (do
            (.pop ptokens-q)
            (recur (inc len)))
          len)))))

(defn- next-span [^ArrayDeque ptokens-q]
  (when-not (.isEmpty ptokens-q)
    (let [{:keys [coord kind len interesting? text]} (.pop ptokens-q)]
      (cond

        (= kind :text)
        {:coord coord, :len len, :interesting? interesting?, :text text}

        (= kind :nl)
        {:len (+ 1 (read-contiguous-spaces ptokens-q)) :tab? true}

        (= kind :sp)
        {:len 1}))))

(defn coord-spans
  "Given `ptokens` as generated by `pprint-tokens` return a collection of spans.

  Spans are maps in the form of {:idx-from INT :len INT} with also some special marks.
  - If the print-token contains a :coord it will be added to the span
  - If the print-token is marked as :interesting? so it will be the span
  - The spans generated for tabs (a new line followed by spaces) will be marked as :tab? true
  "
  [ptokens]
  (let [ptokens-q (ArrayDeque. ptokens)]
    (loop [idx-from 0
           spans []]
      (if-let [{:keys [len] :as span} (next-span ptokens-q)]
        (recur (+ idx-from len)
               (conj spans (assoc span :idx-from idx-from)))

        spans))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utilities for the repl ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- debug-print-tokens [ptokens]
  (-> ptokens to-string println))

(defn pprint-form-hl-coord [form c]
  (let [tokens (pprint-tokens form)]
    (doseq [{:keys [kind text coord]} tokens]
      (let [txt (case kind
                  :sp " "
                  :nl "\n"
                  :text (if (= coord c)
                          (utils/colored-string text :red)
                          text))]
        (print txt)))))

(comment

  (let [test-form '(defn factorial [n] (if (zero? n) 1 (* n (factorial (dec n)))))]
    (binding [pp/*print-right-margin* 40
              pp/*print-pprint-dispatch* pp/code-dispatch]
      (= (-> test-form
             (pprint-tokens)
             debug-print-tokens
             with-out-str)
         (-> test-form
             pp/pprint
             with-out-str))))
  (form-tokens (with-meta '(+ 1 2) {::coord [1]}))
  (def test-form '(defn clojurescript-version
                    "Returns clojurescript version as a printable string."
                    []
                    (fn* [p1__12449#] (+ 1 p1__12449#))
                    (if (bound? #'*clojurescript-version*)
                      (str
                       (:major *clojurescript-version*)
                       "."
                       (:minor *clojurescript-version*)
                       (when-let [i (:incremental *clojurescript-version*)]
                         (str "." i))
                       (when-let [q (:qualifier *clojurescript-version*)]
                         (str "." q))
                       (when (:interim *clojurescript-version*)
                         "-SNAPSHOT"))
                      @synthetic-clojurescript-version)))

  (binding [pp/*print-right-margin* 80
              pp/*print-pprint-dispatch* pp/code-dispatch]
    (->> test-form
         (pprint-tokens)
         #_debug-print-tokens))

  )
