(ns zen.package
  (:require
   clojure.edn
   [clojure.java.shell :as shell]
   ;; [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.test :as t]))

(defn sh [& args]
  (println "$" (clojure.string/join " " args)
           (let [result (apply shell/sh args)]
             (println (:out result))
             result)))

(defn get-git-hash [path]
  (def path "/tmp/a")
  (as-> (slurp (str path "/.git/HEAD")) v
    (string/trim-newline v)
    (subs v 5)
    (str path "/.git/" v)
    (slurp v)
    (string/trim-newline v)))

(def root "/tmp/zen")

(defn git-init [path] (sh "git" "init" :dir path))

(defn init-stub-dependencies! []
  (doseq [repo [{:name "/a"
                 :deps [['c "/tmp/c"]]}
                {:name "/b"
                 :deps [['c "/tmp/c"]]}
                {:name "/c"
                 :deps []}]
          :let [dir (str "/tmp" (:name repo))
                deps (:deps repo)]]
    (sh "rm" "-rf" dir)
    (sh "mkdir" "-p" dir)
    (git-init dir)
    (spit (str dir "/main.edn") (str {'ns 'main (symbol (str (random-uuid))) {}}))
    (spit (str dir "/package.edn") (str deps))
    (sh "git" "add" "." :dir dir)
    (sh "git" "commit" "-m" "\"Initial commit\"" :dir dir)))

(defn zen-clone! [root package-file]
  (->> package-file
       slurp
       clojure.edn/read-string
       (run! (fn [[alias url]]
               (let [dest (str root "/zen_modules/" (string/replace (str alias) #"\." "/"))]
                 (do
                   (sh "git" "clone" url dest)
                   (zen-clone! dest (str dest "/package.edn"))))))))

(def zrc (str root "/zrc"))

(defn zen-init! [root]
  (let [pkg-file (str zrc "/../package.edn")]
    (sh "rm" "-rf" root)
    (.mkdir (java.io.File. root))
    (.mkdir (java.io.File. zrc))

    (spit (str zrc "/main.edn") (str '{ns main}))

    (git-init root)
    (spit (str root "/.gitignore") "/zen_modules")

    (def precommit-hook-file (str root "/.git/hooks/pre-commit"))
    (spit precommit-hook-file "#!/bin/bash \n\necho \"hello world!\"")
    (sh "chmod" "+x" precommit-hook-file)

    (spit pkg-file (str '[[a "/tmp/a"]
                              [b.dir "/tmp/b"]]))
    (zen-clone! root pkg-file)))

(t/deftest zen-pm
  (t/testing "Zen can recursively load dependencies"
    (init-stub-dependencies!)
    (zen-init! root)
    (t/is (= (get-git-hash "/tmp/a")
             (get-git-hash "/tmp/zen/zen_modules/a")))
    (t/is (= (get-git-hash "/tmp/c")
             (get-git-hash "/tmp/zen/zen_modules/a/zen_modules/c")))

    (t/is (= (get-git-hash "/tmp/b")
             (get-git-hash "/tmp/zen/zen_modules/b/dir")))
    (t/is (= (get-git-hash "/tmp/c")
             (get-git-hash "/tmp/zen/zen_modules/b/dir/zen_modules/c")))))

(defn copy! [& from-to] (apply sh "cp" "-r" from-to))
(defn flat-dir! [dir to] (copy! dir to))
(defn dir-list [dir] (-> dir clojure.java.io/file .list))
(defn clear-files! [dir & files] (apply sh "rm" "-rf" (map #(str dir "/" %) files)))
(defn recur-flat! [dir]
  (flat-dir! (str dir "/zrc/.") dir)
  (flat-dir! (str dir "/zen_modules/.") dir)
  (doseq [mdir  (dir-list dir)
          :let  [mdir (str dir "/" mdir)]
          :when (not-empty (dir-list mdir))]
    (recur-flat! mdir))
  (clear-files! dir "package.edn" "zen_modules" ".git"))

(defn build! []
  (let [build-dir (str root "/build")]
    (sh "rm" "-rf" build-dir)
    (sh "mkdir" build-dir)
    (copy! zrc (str root "/zen_modules") build-dir)
    (recur-flat! build-dir)
    (sh "rm" "-rf" (str build-dir "/zen_modules") (str build-dir "/zrc"))
    (sh "zip" "-r" "uberzen.zip" "." :dir build-dir)))
