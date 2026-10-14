# Translations

A developer-facing reference for the translations pass (AGENTS *Translations*), so each language
keeps the terms and voice it started with. What ships and why is in SPEC *Language*.

## Languages and folders

| Language | Folder | Plural quantities | Notes |
|---|---|---|---|
| Romanian | `values-ro` | one, few, other | comma-below ș ț, never cedilla ş ţ; `other` takes "de" ("20 de stații") |
| Spanish | `values-es`, `values-b+es+419` | one, many, other | neutral, read alike in Spain and Latin America; `b+es+419` overrides only the quoted permission buttons |
| Polish | `values-pl` | one, few, many, other | `other` (fractions) is the genitive singular |
| Bengali | `values-bn` | one, other | standard Bangladeshi written Bengali |
| Portuguese | `values-pt`, `values-pt-rBR` | one, many, other | European; `pt-rBR` overrides only what differs |
| Italian | `values-it` | one, many, other | |
| Turkish | `values-tr` | one, other | the noun stays singular after a number |
| German | `values-de` | one, other | runs long: prefer the short word |
| French | `values-fr` | one, many, other | `one` covers 0 and 1 |

`many` (large round numbers) reads as `other`. Every item keeps the number, `one` included.
Each folder is language-only, so every region reads it (`RegionalLocalesTest`); a region folder
overrides only what differs, as `values-en-rGB` does for English.

## Rules for every language

- **A placeholder holds an English name or a number** (a stop, line, place, time, distance), so
  nothing may inflect it: no case ending or suffix on it, and no article, elision or preposition
  whose form depends on it. Restructure instead: a colon form ("Ziel: %1$s"), an invariant
  preposition, an arrow, or a declined noun beside it ("%1$s durağında").
- Keep every format spec of the base, each once; reordering is fine.
- Don't translate `StopDash`, TfL names and brands (line names, `Tube`, `London Overground`,
  `DLR`, `National Rail`, `Oyster`) or station and place names. "London" in running prose takes
  the language's own name for the city (Londres, Londra, Londyn, লন্ডন).
- `about_data_sources` keeps its licence statements (OGL, Ordnance Survey, Royal Mail, National
  Statistics) in English, word for word as the licences ask; its other sentences are translated.
- Concise: a widget row, tile or pill has the English's room. Status words stay very short.
- Quoted Android permission buttons ("Allow all the time") must match that language's real
  dialog, region included: Android words them differently in Spain (`values-es`) and Latin America
  (`values-b+es+419`), and in Portugal (`values-pt`) and Brazil (`values-pt-rBR`). The first pass
  quoted them from memory (TODO *Name "Allow all the time" from the device*).

## Voice

| Language | Address | Buttons | Other conventions |
|---|---|---|---|
| ro | polite plural ("dvs.") | imperative ("Salvați") | progress "Se încarcă…"; errors "Nu s-a putut …"; est. → "estim." |
| es | tú | infinitive ("Guardar") | errors "No se pudo …"; est. → "aprox." |
| pl | informal, "Ty/Twój" capitalized | imperative ("Zapisz") | progress "Wczytuję…"; errors "Nie udało się …"; tap → "Kliknij"; no gendered past forms; est. → "szac." |
| bn | আপনি | polite imperative ("সেভ করুন") | sentences end "।"; min → "মি." compact, "মিনিট" in sentences; est. → "আনু." |
| pt | formal, no "você" ("Toque", "o seu", "perto de si"); pt-BR overrides use "você" | infinitive ("Guardar") | progress "A carregar…" (pt-BR gerund "Carregando…"); "a app" (pt-BR "o app"); est. → "prev." |
| it | tu | imperative ("Aggiorna") | errors "Impossibile + infinitive"; est. → "stima" |
| tr | siz in sentences | bare stem ("Kaydet") | min → "dk" ("dakika" when spoken); est. → "tahmini"; "%%%1$d" puts % first |
| de | du, lowercase | infinitive ("Standort verwenden") | min → "Min."; est. → "ca."; errors short ("Nicht aktualisiert – …") |
| fr | vous | infinitive ("Actualiser") | typographic ’; U+00A0 before : and inside « »; U+202F before ? ! ;; est. → "estim." ("est" is east); a row is "rangée", not "ligne" |

## Terms

The English travel words (TODO *Write `GLOSSARY.md`*): a **ride** is a saved pair of stops with the
lines that run directly between them; a **trip** is the rider's planned From→To, legs and changes
included, and each of the planner's alternatives is one; a **route** is all of a line's runs between
its two termini. Each language keeps the three apart.

| English | ro | es | pl | bn | pt | it | tr | de | fr |
|---|---|---|---|---|---|---|---|---|---|
| stop | stație | parada | przystanek | স্টপ | paragem (pt-BR parada) | fermata | durak | Haltestelle | arrêt |
| station | stație | estación | stacja | স্টেশন | estação | stazione | istasyon | Station | station |
| departure | plecare | salida | odjazd | প্রস্থান | partida | partenza | kalkış | Abfahrt | départ |
| arrival | sosire | llegada | przyjazd | পৌঁছানো | chegada | arrivo | varış | Ankunft | arrivée |
| line | linie | línea | linia | লাইন | linha | linea | hat | Linie | ligne |
| platform | peron | andén | peron | প্ল্যাটফর্ম | plataforma | binario | peron | Gleis | quai |
| ride (a favorite ride) | cursă | trayecto | przejazd | যাত্রা | trajeto | tragitto | biniş | Strecke | trajet |
| trip | călătorie | viaje | podróż (a planner's option: połączenie) | ট্রিপ | viagem | viaggio | yolculuk | Fahrt | itinéraire |
| route | rută | ruta | trasa | রুট | percurso | percorso | güzergâh | Route | parcours |
| Disruption ahead | Perturbări pe parcurs | Incidencias más adelante | Utrudnienia na dalszej drodze | সামনে বিঘ্ন | Perturbação à frente (pt-BR Interrupção à frente) | Disservizio più avanti | İleride aksaklık | Störung voraus | Perturbation en vue |
| favourite | preferat | favorito | ulubione | প্রিয় | favorito | preferito | favori | Favorit | favori |
| pin to top | Fixați sus | Fijar arriba | Przypnij na górze | পিন করুন | Fixar no topo | Fissa in alto | Üste sabitle | Oben anpinnen | Épingler en haut |
| refresh | Actualizați | Actualizar | Odśwież | রিফ্রেশ করুন | Atualizar | Aggiorna | Yenile | Aktualisieren | Actualiser |
| widget | widget | widget | widżet | উইজেট | widget | widget | widget | Widget | widget |
| watch | ceas | reloj | zegarek | ঘড়ি | relógio | orologio | saat | Smartwatch | montre |
| disruption | perturbare | incidencia | utrudnienie | বিঘ্ন | perturbação (pt-BR interrupção) | disservizio | aksaklık | Störung | perturbation |
| delay | întârziere | retraso | opóźnienie | বিলম্ব | atraso | ritardo | gecikme | Verspätung | retard |
| change (trains) | Treceți pe … la | transbordo | przesiadka | … গিয়ে … ধরুন | transbordo (pt-BR baldeação) | cambio | aktarma | umsteigen | correspondance |
| walk | pe jos | a pie | pieszo | হাঁটা | a pé | a piedi | yürüyüş | zu Fuß | à pied |
| settings | Setări | Ajustes | Ustawienia | সেটিংস | Definições (pt-BR Configurações) | Impostazioni | Ayarlar | Einstellungen | Paramètres |
| near me | în apropiere | cerca de ti | w pobliżu | আপনার কাছাকাছি | perto de mim | nelle vicinanze | yakınımdaki | in der Nähe | à proximité |
| step-free | fără trepte | sin escalones | bez schodów | স্টেপ-ফ্রি | sem degraus | senza gradini | basamaksız | stufenfrei | sans marche |
| On the way | Pe drum | En camino | W drodze | যাত্রাপথে | A caminho | In viaggio | Yolda | Unterwegs | En route |
