;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns pushkin.board
  (:require
    [pushkin.hash :as h]
    [pushkin.position :as p]))

;;;

(defn opponent [color]
  (case color
    :white :black
    :black :white))

;;;

(defrecord Board
  [dim
   positions
   empty-positions
   white-score
   black-score
   hash]
  Object
  (toString [_]
    "** BOARD **"))

(def empty-board
  (memoize
    (fn [dim]
      (let [ps (range (* dim dim))]
        (map->Board
          {:dim dim
           :eyes #{}
           :empty-positions (set ps)
           :positions (vec (map #(p/initial-position dim %) ps))
           :white-score 0
           :black-score 0
           :hash (h/zobrist-hash)})))))

(defn print-board [board]
  (let [dim (:dim board)
        cols (->> (range dim)
               (map #(char (+ (int \A) %)))
               (apply str))]
    (println "Black:" (:black-score board))
    (println "White:" (:white-score board))
    (println (str "  " cols "\n"))
    (dotimes [x dim]
      (print (str (inc x) " "))
      (dotimes [y dim]
        (let [c (get-in board [:positions (p/position dim x y) :color])]
          (print
            (case c
              :white "O"
              :black "X"
              :empty "."))))
      (println (str " " (inc x))))
    (println (str "\n  " cols "\n"))))

(defmethod print-method Board [o ^java.io.Writer w]
  (.write w (str o)))

;;;

(defmacro def-accessor [name field]
  `(defn ~name [board# pos#]
     (get-in board# [:positions pos# ~field])))

(declare parent color)

(defmacro def-parent-accessor [name field]
  `(defn ~name [board# pos#]
     (get-in board# [:positions (parent board# pos#) ~field])))

(defn neighbors
  ([board pos]
     (neighbors board pos (constantly true)))
  ([board pos predicate]
     (->> pos
       (p/neighbors (:dim board))
       (filter #(predicate (color board %))))))

(defn group
  ([board n]
     (if (= :empty (color board n))
       #{n}
       (group board n #{})))
  ([board n group-set]
     (if (group-set n)
       group-set
       (let [group-set (conj group-set n)]
         (reduce
           #(group board %2 %1)
           group-set
           (neighbors board n #{(color board n)}))))))

(defn position-range [board]
  (let [dim (:dim board)]
    (range (* dim dim))))

;;;

(def-accessor color :color)
(def-parent-accessor liberties :liberties)
(def-accessor white-neighbors :white-neighbors)
(def-accessor black-neighbors :black-neighbors)
(def-accessor local-parent :parent)
(def-parent-accessor neighbor-sum :neighbor-sum)
(def-parent-accessor neighbor-sum-of-squares :neighbor-sum-of-squares)

(defn update-color [board pos color]
  (assoc-in board [:positions pos :color] color))

(defn parent [board pos]
  (loop [pos pos]
    (let [p (local-parent board pos)]
      (if (= p pos)
        p
        (recur p)))))

(defn penultimate-parent [board pos]
  (let [n (local-parent board pos)
        nn (local-parent board n)]
    (loop [a pos, b n, c nn]
     (if (= b c)
       a
       (recur b c (local-parent board c))))))

(defn atari? [board pos]
  (let [sum (neighbor-sum board pos)
        sum-of-squares (neighbor-sum-of-squares board pos)]
    (= (* sum sum) (* (liberties board pos) sum-of-squares))))

;;;

(defn calculate-group [board n]
  (let [p (parent board n)]
    (->> board
      position-range
      (filter #(= p (parent board %)))
      set)))

(defn calculate-sum-of-squares [board pos]
  (->> pos
    (group board)
    (mapcat #(neighbors board % #{:empty}))
    (map #(* % %))
    (apply +)))

(defn calculate-sum [board pos]
  (->> pos
    (group board)
    (mapcat #(neighbors board % #{:empty}))
    (apply +)))

(defn calculate-liberties [board pos]
  (->> pos
    (group board)
    (mapcat #(neighbors board % #{:empty}))
    count))

(defn calculate-black-neighbors [board pos]
  (->> (neighbors board pos #{:black})
    count))

(defn calculate-white-neighbors [board pos]
  (->> (neighbors board pos #{:white})
    count))

(defn calculate-atari? [board pos]
  (->> pos
    (group board)
    (mapcat #(neighbors board % #{:empty}))
    set
    count
    (= 1)))

(def validations
  [[:white-neighbors (constantly true) white-neighbors calculate-white-neighbors]
   [:black-neighbors (constantly true) black-neighbors calculate-black-neighbors]
   [:sum #{:white :black} neighbor-sum calculate-sum]
   [:sum-of-squares #{:white :black} neighbor-sum-of-squares calculate-sum-of-squares]
   [:group #{:white :black} group calculate-group]
   [:liberties #{:white :black} liberties calculate-liberties]
   [:atari #{:white :black} atari? calculate-atari?]])

(defn validate-positions
  ([board]
     (validate-positions board validations))
  ([board validations]
     (doseq [p (position-range board)]
       (doseq [[field predicate lookup from-scratch] validations]
         (try
           (when (predicate (color board p))
             (assert
               (= (lookup board p) (from-scratch board p))
               (str field " at " (p/position->gtp p (:dim board)) ": " (lookup board p) ", " (from-scratch board p))))
           (catch Throwable e
             (print-board board)
             (throw e)))))
     board))

;;;

(defn capture-type [board color p]
  (when-let [n (->> (neighbors board p #{(opponent color)})
                 (filter #(atari? board %))
                 first)]
    (if (and (= n (parent board n))
          (-> board
            :hash
            h/rotate-hashes
            (h/update-hash p color)
            (h/update-hash n (opponent color))
            h/cycle?))
      :ko
      :valid)))

(defn eye-type [board n]
  (let [num-neighbors (count (p/neighbors (:dim board) n))]
    (or
      (and
        (= num-neighbors (white-neighbors board n))
        (not (capture-type board :black n))
        :white)
      (and
        (= num-neighbors (black-neighbors board n))
        (not (capture-type board :white n))
        :black))))

(defn final-score [board]
  (merge-with +
    {:white (:white-score board)
     :black (:black-score board)}
    (->> (position-range board)
      (map #(eye-type board %))
      (remove false?)
      frequencies)))
