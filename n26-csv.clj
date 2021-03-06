(ns n26.csv
  "Converts N26 2018 CSV-file format (as exported by N26 banking) to a
  KMyMoney importable format.

  Version 0.1.0

  Requires Babashka -- https://github.com/borkdude/babashka
  Requires spartan.spec -- https://github.com/borkdude/spartan.spec

  See deps.edn and n26-csv bash script.

  DEVELOPMENT
  ===========
  Start nrepl: `bb -cp \"$(clojure -Spath)\" --nrepl-server`
  Emacs: `cider-connect-clj` with reported port and `cider-repl-set-ns`.

  DISCLAIMER: THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
  CONTRIBUTORS \"AS IS\" AND ANY EXPRESS OR IMPLIED WARRANTIES,
  INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
  MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
  DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
  BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY,
  OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
  PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
  PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
  OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
  (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
  USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH
  DAMAGE.

  Twitter: @maridonkers | Google+: +MariDonkers | GitHub: maridonkers"
  
  (:require [clojure.string :as str]
            [spartan.spec :as s]
            [clojure.java.io :as io]))

(defn extname
  "Gets file extension for path."
  [path]
  (let [ext (second (re-find #"^.*(\.[^.]+)$" path))]
    (if (empty? ext) "" ext)))

(defn basename
  "Gets basename for path with specified file extension."
  [path ext]
  (let [base (if (empty? ext)
               path
               (second (re-find (re-pattern (str "^(.*)(?:" ext ")$")) path)))]
    base))

(defn exists
  "Check if a path exists."
  [path]
  (let [f (io/file path)]
    (.exists f)))

;; -------
;; N26 CSV

(def DATE-REGEXP #"([0-9]{4})-([0-9]{2})-([0-9]{2})")
(def BBAN-REGEXP #"(?i)P?[0-9]+")
(def IBAN-REGEXP #"(?i)[A-Z]{2}[0-9]{2}[A-Z0-9]{4,}")
(def AMOUNT-REGEXP #"[+-]?[0-9]+\.?[0-9]*")
(def EXCHANGE-RATE-REGEXP #"[0-9]+\.?[0-9]*")

(s/def ::iban-bban (s/and string?
                          #(<= (count %) 34)
                          (s/or :empty #(= (count %) 0)
                                :bban #(re-matches BBAN-REGEXP %)
                                :iban #(re-matches IBAN-REGEXP %))))

(s/def ::date (s/and string?
                     #(<= (count %) 10)
                     #(re-matches DATE-REGEXP %)))

(s/def ::amount-eur (s/and string? #(re-matches AMOUNT-REGEXP %)))

(s/def ::amount-foreign (s/and string? (s/or :empty #(= (count %) 0)
                                             :amount #(re-matches AMOUNT-REGEXP %))))

(s/def ::exchange-rate (s/and string? (s/or :empty #(= (count %) 0)
                                            :rate #(re-matches EXCHANGE-RATE-REGEXP %))))

(s/def ::currency-type string?)

(s/def ::payee string?)

(s/def ::transaction-type string?)

(s/def ::description string?)

(s/def ::category string?)

;; "Date","Payee","Account number","Transaction type","Payment reference","Category","Amount (EUR)","Amount (Foreign Currency)","Type Foreign Currency","Exchange Rate"
;; "2018-09-20","Business Inc.","NL00RABO0123456789","Income","Ping","Miscellaneous","0.88","1.0","USD","0.8821879"
;; "2018-09-20","Business Inc.","NL00RABO0123456789","Outgoing Transfer","Pong","Miscellaneous","-0.88","-1.0","USD","0.8821879"

;; Veld	Omschrijving			Type		Lengte	Inhoud/Toelichting
(s/def ::csv-columns
  (s/cat
   
   ;; 1	DATE		  	        Date		10	Date. Format: EEJJ-DD-MM; For example: 2017-31-07
   :1 ::date

   ;; 2	PAYEE				Alfanumeriek	n/a	Payee
   :2 ::payee

   ;; 3	ACCOUNT_NUMBER			Alfanumeriek	n/a	Account number
   :3 ::iban-bban

   ;; 4	TRANSACTION_TYPE 		Alfanumeriek	n/a	Transaction type
   :4 ::transaction-type

   ;; 5	PAYMENT_REFERENCE 		Alfanumeriek	n/a	Payment reference
   :5 ::description

   ;; 6	CATEGORY			Alfanumeriek	n/a	Category
   :6 ::category

   ;; 7	AMOUNT_EUR			Numeriek	n/a	Prefix +/-; decimals are represented with a point (.)
   :7 ::amount-eur

   ;; 8	AMOUNT_FOREIGN_CURRENCY		Numeriek	n/a	Prefix +/-; decimals are represented with a point (.)
   :8 ::amount-foreign

   ;; 9	TYPE_FOREIGN_CURRENCY		Alfanumeriek	n/a	Type of currency (e.g. EUR, USD, etc.).
   :9 ::currency-type

   ;; 10 EXCHANGE_RATE			Numeriek	n/a	Decimals are represented with a point (.)
   :10 ::exchange-rate))

;; ---------
;; KYMYMONEY
;;
;; Beschrijving uitvoerformaat (which is a KMyMoney compatible import formaat).
;;
;; Veld	Omschrijving			Type
;; 1	NUMBER				Alfanumeriek
;; 2	DATE				Numeriek
;; 3	DEBIT/CREDIT			Numeriek
;; 4	CATEGORY (code)			Alfanumeriek
;; 5	PAYEE  				Alfanumeriek
;; 6	MEMO (concatenated fields)	Alfanumeriek
;;
;; The MEMO field in the output is a concatenation of various fields
;; taken from the N26 CSV input. Spaces are inserted where required, (to
;; accommodate readability.)
;;

;; Set with output filenames, which is used to delete existing files
;; only once.
(def output-fnames (atom #{}))

(defn get-csv-columns
  "Gets CSV columns as vector. The enclosing quotes are
  removed. Nested quotes are not allowed in N26 CSV."
  [csv-line]
  (let [columns (map second (re-seq #"\"([^\"]*)\"" csv-line))]
    
    (when-not (s/valid? ::csv-columns columns)
      (println (str "\t" (s/explain ::csv-columns columns))))
    columns))

(defn convert-description
  "Converts description."
  [cvs]

  (let [[_ _
         account-number
         transaction-type
         payment-reference
         category
         _
         amount-foreign-currency
         type-foreign-currency
         exchange-rate] cvs

        transaction-type (if (str/blank? transaction-type)
                           transaction-type
                           (str transaction-type "; "))
        
        category (if (str/blank? category)
                   category
                   (str category "; "))

        extra (->> [transaction-type category
                    amount-foreign-currency type-foreign-currency exchange-rate]
                   (map str/trim)
                   (interpose " ")
                   (filter #(seq %))
                   (apply str)
                   str/trim)]

    (str (when (seq account-number) (str "[" account-number "] "))
         (str payment-reference
              (when (seq extra) (str (when (seq payment-reference) " ") extra))))))

(defn convert-columns
  "Converts columns in input CSV line to columns in output CSV line."
  [csv]

  (let [[date
         payee
         _
         _
         _ _
         amount
         _ _ _] csv
        
        result [""
                date
                amount
                ""
                payee
                (convert-description csv)]]

    (str (->> result
              (map #(str "\"" (if (empty? %) " " %) "\""))
              (interpose ",")
              (apply str))
         "\n")))

(defn convert-line
  "Converts CSV line. Appends to output file."
  [fname csv-line]
  (let [csv (get-csv-columns csv-line)
        ofpostfix "kmymoney"
        ext (extname fname)
        base (basename fname ext)
        ofname (str base "#" ofpostfix ext)]
    (when (and (exists ofname)
               (not (contains? @output-fnames ofname)))
      (do (io/delete-file ofname)))

    ;; If the file already existed it was deleted and if didn't exist
    ;; it was also okay. So always add it to the set of output-fnames.
    (swap! output-fnames conj ofname)

    (spit ofname
          (convert-columns csv)
          :append true)

    ofpostfix))

(defn convert
  "Converts CSV lines."
  [fname csv-str]
  (->> csv-str
       str/split-lines
       rest
       (map (partial convert-line fname))))

;; ----
;; MAIN

(defn main [fnames]
  (doseq [fname fnames]
    (println (str fname ":"))
    (let [lines (slurp fname :encoding "utf8")
          accounts (distinct (convert fname lines))]

      (println (str "\t"
                    (->> accounts
                         (interpose "\n")
                         (apply str)))))))

(if (empty? *command-line-args*)
  (println "Usage: n26-csv <filename> {<filename> ...}")
  (main *command-line-args*))
