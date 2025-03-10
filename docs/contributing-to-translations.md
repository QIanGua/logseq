## Intro

Thanks for your interest in improving our translations! This document provides
details on how to contribute to a translation. This document assumes you can run
commandline tools, know how to switch languages within Logseq and basic
Clojurescript familiarity. We use [tongue](https://github.com/tonsky/tongue), a
most excellent library, for our translations.

## Setup

In order to run the commands in this doc, you will need to install
[Babashka](https://github.com/babashka/babashka#installation).

## Where to Contribute

Language translations are in two files,
[frontend/dicts.cljs](https://github.com/logseq/logseq/blob/feature/lang-tasks-and-ci/src/main/frontend/dicts.cljs)
and
[shortcut/dict.cljs](https://github.com/logseq/logseq/blob/feature/lang-tasks-and-ci/src/main/frontend/modules/shortcut/dict.cljs).
When translating `shortcut/dict.cljs` you will want to refer to
https://github.com/logseq/logseq/blob/feature/lang-tasks-and-ci/src/main/frontend/modules/shortcut/config.cljs
for the English equivalent.

## Language Overview

First, let's get an overview of Logseq's languages and how many translations your
language has compared to others:

```sh
$ bb lang:list


|  :locale | :percent-translated | :translation-count |              :language |
|----------+---------------------+--------------------+------------------------|
|      :en |                 100 |                494 |                English |
|   :nb-NO |                  90 |                445 |         Norsk (bokmål) |
|   :zh-CN |                  87 |                432 |                   简体中文 |
|      :ru |                  85 |                422 |                Русский |
|   :pt-BR |                  77 |                382 | Português (Brasileiro) |
|   :pt-PT |                  76 |                373 |    Português (Europeu) |
|      :es |                  71 |                349 |                Español |
| :zh-Hant |                  55 |                272 |                   繁體中文 |
|      :af |                  51 |                253 |              Afrikaans |
|      :de |                  48 |                238 |                Deutsch |
|      :fr |                  39 |                195 |               Français |
Total: 11
```

Let's try to get your language translated as close to 100% as you can!

## Edit a Language

To see what translations are missing:

```
$ bb lang:missing
|                       :translation-key |                                  :string-to-translate |
|----------------------------------------+-------------------------------------------------------|
|                            :cards-view |                                            View cards |
|                                :delete |                                                Delete |
|                          :export-graph |                                          Export graph |
|                           :export-page |                                           Export page |
|                          :graph-search |                                          Search graph |
|                       :open-new-window |                                            New window |
...
```

Now, add keys for your language, save and rerun the above command. Over time
you're hoping to have this list drop to zero.

There is a lot to translate and sometimes we make mistakes. For example, we may leave a string untranslated. To see what translation keys are still left in English:

```
$ bb lang:duplicates
Keys with duplicate values found:

|                  :translation-key | :duplicate-value |
|-----------------------------------+------------------|
|                          :general |          General |
|                           :logseq |           Logseq |
|                               :no |               No |
```

Sometimes, we typo the translation key. If that happens, the github CI job will
detect this error and helpfully show you what was typoed.

## Add a Language

To add a new language, add an entry to `frontend.dicts/languages`. Then add a
new locale keyword to `frontend.dicts/dicts` and to
`frontend.modules.shortcut.dict/dict` and start translating as described above.
