(ns zen.package
  (:require [clojure.string :as str]
            [clojure.java.shell :as shell]
            [clojure.java.io :as io]
            [clojure.edn :as edn]))


(defn sh! [& args]
  (println "$" (str/join " " args))
  (let [result (apply shell/sh args)]
    (when-let [out (:out result)]
      (print out))
    result))


(defn sh&&! [call & calls]
  (let [{:as result, :keys [exit]} (apply sh! call)]
    (if (and (= 0 exit) (seq calls))
      (apply sh&&! calls)
      result)))


(defn mkdir! [path name]
  (sh! "mkdir" "-p" name :dir path))


(defn read-deps [root]
  (let [package-file (->> (str root "/zen-package.edn")
                          slurp
                          edn/read-string)]
    (:deps package-file)))


(defn init-pre-commit-hook! [root]
  (let [precommit-hook-file (str root "/.git/hooks/pre-commit")]
    (spit precommit-hook-file "#!/bin/bash \n\necho \"hello world!\"")
    (sh! "chmod" "+x" precommit-hook-file)))


(defn append-gitignore-zen-modules [root]
  (let [gitignore-path (str root "/.gitignore")
        gitignore      (when (.exists (io/file gitignore-path))
                         (slurp gitignore-path))]
    (when-not (str/includes? (str gitignore) "/zen-modules\n")
      (spit (str root "/.gitignore") "\n/zen-modules\n" :append true))))


(defn zen-init! [root] #_"TODO: templating goes here"
  (sh! "git" "init" :dir root)
  (mkdir! root "zrc")
  (init-pre-commit-hook! root)
  (append-gitignore-zen-modules root))


(defn zen-init-deps-recur! [root deps]
  (loop [[[dep-name dep-url] & deps-to-init] deps
         initted-deps #{}]
    (cond
      (nil? dep-name)
      initted-deps

      (contains? initted-deps dep-name)
      (recur deps-to-init
             initted-deps)

      :else
      (let [dep-name-str (name dep-name)]
        (sh! "git" "clone" (str dep-url) dep-name-str
             :dir root)
        (recur
          (concat (read-deps (str root "/" dep-name-str))
                  deps-to-init)
          (conj initted-deps dep-name))))))


(defn zen-init-deps! [root]
  (mkdir! root "zen-modules")

  (zen-init-deps-recur!
    (str root "/zen-modules")
    (read-deps root)))


#_(defn copy! [& from-to] (apply sh! "cp" "-r" from-to))


#_(defn flat-dir! [dir to] (copy! dir to))


#_(defn dir-list [dir] (-> dir clojure.java.io/file .list))


#_(defn clear-files! [dir & files]
  (apply sh! "rm" "-rf" (map #(str dir "/" %) files)))


#_(defn recur-flat! [dir]
  (flat-dir! (str dir "/zrc/.") dir)
  (flat-dir! (str dir "/zen_modules/.") dir)
  (doseq [mdir  (dir-list dir)
          :let  [mdir (str dir "/" mdir)]
          :when (not-empty (dir-list mdir))]
    (recur-flat! mdir))
  (clear-files! dir "package.edn" "zen_modules" ".git"))


#_(defn zen-build! [root]
  (let [build-dir (str root "/build")
        zrc (str root "/zrc")]
    (sh! "rm" "-rf" build-dir)
    (sh! "mkdir" build-dir)
    (copy! zrc (str root "/zen_modules") build-dir)
    (recur-flat! build-dir)
    (sh! "rm" "-rf" (str build-dir "/zen_modules") (str build-dir "/zrc"))
    (sh! "zip" "-r" "uberzen.zip" "." :dir build-dir)))
