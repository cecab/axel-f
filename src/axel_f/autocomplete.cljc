(ns axel-f.autocomplete
  (:require [clojure.string :as string]
            [axel-f.core :as axel-f]
            [axel-f.functions :as functions]
            [clj-fuzzy.metrics :as fuzzy]))

(defn- fuzzy-match? [ex dict]
  (->> dict
      (map #(hash-map :value %
                      :distance (if (string/starts-with? (string/lower-case %)
                                                         (string/lower-case ex))
                                  0
                                  (fuzzy/jaccard ex %))))
      (filter #(< (:distance %) 0.6))
      (sort-by :distance)
      (map :value)))

(defn- fix-up-objref [ref]
  (string/replace ref #"\.$" ""))

(defn- valid-reference? [ref]
  (try
    (let [ref (axel-f/compile (fix-up-objref ref))]
      (and (seqable? ref) (= (first ref) :OBJREF)))
    (catch #?(:clj clojure.lang.ExceptionInfo
              :cljs ExceptionInfo) _
      false)))

(defn- fix-up-fncall [fncall]
  (let [fncall (string/replace fncall #",[ ]*$" "")]
    (str fncall
         (when (> (count (filter #{\(} fncall))
                  (count (filter #{\)} fncall)))
           ")"))))

(defn- valid-fncall? [fncall]
  (try
    (let [fncall (axel-f/compile (fix-up-fncall fncall))]
      (and (seqable? fncall) (= (first fncall) :FNCALL)))
    (catch #?(:clj clojure.lang.ExceptionInfo
              :cljs js/Error) _
      false)))

(defn- build-suggestion [type item & [args]]
  (merge {:type type}
         (case type
           :OBJREF {:value (axel-f/->string item)
                    :description "Field in the context"}
           :FN (merge {:value item}
                      (select-keys (get functions/functions-map item)
                                   [:description :args]))
           :FNCALL (merge {:value item
                           :current-arg (let [position (dec (count args))]
                                          (if (< position 0)
                                            0
                                            position))}
                          (select-keys (get functions/functions-map item)
                                       [:description :args])))))

(defn- build-new-context [context path maybe-path]
  (let [new-context (axel-f/run (string/join "." path)
                      context)]
    (cond
      (map? new-context) (->> new-context
                             keys
                             (map axel-f/->string)
                             (fuzzy-match? maybe-path)
                             (map #(build-suggestion :OBJREF %)))
      (seqable? new-context) (if-let [seq-of-maps (not-empty (filter map? new-context))]
                               (->> seq-of-maps
                                   (apply merge)
                                   keys
                                   (map axel-f/->string)
                                   dedupe
                                   (fuzzy-match? maybe-path)
                                   (map #(build-suggestion :OBJREF %)))
                               []))))

(defn- build-suggestions-for-objref [objref context]
  (concat (->> functions/functions-map
              keys
              (fuzzy-match? objref)
              (map #(build-suggestion :FN %)))
          (let [[_ & fields] (axel-f/compile (fix-up-objref objref))]
            (cond
              (> (count fields) 1)
              (let [known-path (map (fn [c]
                                      (if (= "*" c)
                                        "[]"
                                        c))
                                    (if (string/ends-with? objref ".")
                                      fields
                                      (butlast fields)))
                    maybe-path (if (string/ends-with? objref ".")
                                 ""
                                 (last fields))]
                (cond
                  (= maybe-path "*") (->> (axel-f/run (string/join "." known-path)
                                           context)
                                         (filter map?)
                                         (apply merge)
                                         keys
                                         (map #(build-suggestion :OBJREF %)))
                  :otherwise (build-new-context context known-path maybe-path)))

              (string/ends-with? objref ".")
              (build-new-context context fields "")

              :otherwise
              (->> context
                  keys
                  (map axel-f/->string)
                  (fuzzy-match? (first fields))
                  (map #(build-suggestion :OBJREF %)))))))

(defn- build-suggestions-for-fncall [fncall]
  (let [[_ f args] (axel-f/compile (fix-up-fncall fncall))]
    (->> functions/functions-map
        keys
        (filter #(= % f))
        (map #(build-suggestion :FNCALL % args)))))

(defn get-last-part [incomplete-formula]
  (let [chx (string/split incomplete-formula #"")]
    (loop [ch (last chx) chx (butlast chx) acc [] terms {:open-round       0
                                                         :close-round      0
                                                         :unbalanced-quote false
                                                         :pre-terminate    false
                                                         :terminate        false}]
      (cond
        (and (= ch " ")
             (not (:unbalanced-quote terms)))
        (recur (last chx) (butlast chx) acc terms)

        (and (not= ch "\"")
             (:unbalanced-quote terms))
        (recur (last chx) (butlast chx) (cons ch acc) terms)

        :otherwise
        (if (or (not ch) (:terminate terms))
          (apply str acc)
          (let [terms (cond-> terms
                        (= "\"" ch)                    (update :unbalanced-quote not)
                        (= ")" ch)                     (update :close-round inc)
                        (= "(" ch)                     (#(if (or (valid-reference? (apply str acc))
                                                                 (> (:open-round %)
                                                                    (:close-round %)))
                                                           (assoc % :terminate true)
                                                           (-> %
                                                               (update :open-round inc)
                                                               (assoc :pre-terminate false))
                                                           ))
                        (= "," ch)                     (#(if (valid-reference? (apply str acc))
                                                           (assoc % :terminate true)
                                                           (assoc % :pre-terminate false)))
                        (re-matches #"[A-Z]" ch)       (#(if (>= (:open-round %) (:close-round %))
                                                           (assoc % :pre-terminate true)
                                                           %))
                        (not (re-matches #"[A-Z]" ch)) (#(if (:pre-terminate %)
                                                           (assoc % :terminate true)
                                                           %)))]
            (if (:terminate terms)
              (apply str acc)
              (recur (last chx) (butlast chx) (cons ch acc) terms))))))))

(defn autocomplete
  ([incomplete-formula] (autocomplete incomplete-formula {}))
  ([incomplete-formula context]
   (let [last-part (get-last-part incomplete-formula)]
     (or (cond
           (valid-reference? last-part) (build-suggestions-for-objref last-part context)
           (valid-fncall? last-part)    (build-suggestions-for-fncall last-part))
         []))))
