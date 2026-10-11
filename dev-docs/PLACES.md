# Searching for places

**Status: option 4, the bundled London index, is built** (maintainer, 2026-10-11; tier B, public
places and confident businesses). This is the developer-facing write-up of how the *To…* search finds
arbitrary destinations (a landmark, a shop, an address, a street), with the options that were
weighed. `scripts/build_place_index.py` builds the index and SPEC *Finding stops* describes how the
app uses it.

## Where it stands

- *To…* (and, with #768, *From…* and adding a favorite place) searches the **bundled index** on the
  device: places, streets and postcodes, listed with the stops, each with its position.
- **TfL's Journey Planner** geocoder is the fallback, asked unless the index has a close match (a
  prefix or substring, not just letters in order) and the query isn't an address with a house number,
  which the index can match only loosely.
- So most *To…* searches no longer make the second TfL request (SPEC *Cost*); only the stop search
  goes to TfL.

### Before the index

- *To…* sent the typed text to TfL's Journey Planner, which resolves it to candidate locations.
- TfL's results are poor for places. A well-known landmark can be missing or ranked low, and the
  results arrive in a noisy order. The app re-ranks them with its own name matcher and drops any
  whose name doesn't match.
- Postcodes work well through the same call, and stay tagged *Postcode*.
- Each *To…* search cost a second TfL request for this, on top of the stop search.

## What a better source must do

1. **Find what a Londoner would type.** Landmarks, shops and venues, streets, and ideally street
   addresses. Typo-tolerant, and working from the first few letters.
2. **Return a coordinate**, since a trip to a place is planned to its coordinate (SPEC D9).
3. **Fit the search list's rules.** It shares one list with the stop rows, ranked by match, and a
   row never moves once shown (rows only get appended).
4. **Respect the privacy floor.** Today the typed text goes only to TfL. Any new recipient is a
   Data Safety change, and the rider's position should never be sent.

## The options

### 1. TfL Journey Planner (today)

- **Cost:** £0, within TfL's rate limit.
- **Privacy:** no new recipient.
- **Quality:** poor for places, good for postcodes.
- **Offline:** no.

### 2. Android's built-in `Geocoder`

- **Cost:** £0, no key.
- **Privacy:** the backend depends on the device. Android only says whether a geocoder is present,
  and allows there to be none. It is usually Google's on a phone with Play services, but a
  manufacturer can supply its own. So the typed text goes to whichever service the device uses,
  which we can't name in `docs/PRIVACY.md`. That is still a Data Safety change.
- **Quality:** built to resolve one address, not to suggest as you type. It returns few results
  and is weak at business and venue names. Its behavior varies by device.
- **Verdict:** unlikely to beat TfL by enough to justify the change.

### 3. Google Places Autocomplete (the paused build)

- **How it works:** autocomplete returns suggestions without coordinates. One "place details"
  call on tap supplies the coordinate. A session token links the keystrokes to that one tap.
- **Cost:** billed per session. Check this against Google's current pricing page before
  committing to it:
  - **Autocomplete requests are charged per request for the first 12 in a session**; only those
    after the twelfth are free. This is according to Google's session-pricing page, which
    couldn't be reached to confirm it from here. A typical pick takes a handful of requests, so
    nearly all of them are billed.
  - **The details call** that ends the session is billed too.
  - **An abandoned session** (the rider backs out, or picks a stop instead of a place) has no
    details call to end it, so every one of its autocomplete requests is billed, past the twelfth
    too. In this app that is most searches, since most picks are stops.
  - Both have a monthly free allowance (about 10,000 each). Beyond it, autocomplete is a few
    dollars per 1,000 requests and details about $5 per 1,000 calls. Count both when costing it.
  - At family scale this is effectively $0. It needs a Google Cloud project with billing turned
    on, and an API key restricted to the app.
- **Privacy:** the typed text, the session token and the phone's IP address go to Google.
  `docs/PRIVACY.md` and Data Safety change.
- **Quality:** best of the options.
- **Terms that shape the design:**
  - Results shown away from a Google map need **Google attribution**.
  - Coordinates may be cached for **30 days** at most, and storing other place content is
    restricted. Only the **place ID** may be kept indefinitely. That collides with *Recent*, which
    keeps picks indefinitely.
- **Offline:** no.

### 4. Our own index, bundled in the app (open data)

London's open data is small enough to ship inside the app, the way the station index already is
(`app/src/main/assets/stations`, about 230 KB, rebuilt weekly). The data can come from
OpenStreetMap, from Overture Maps, or from both; the measured sizes below are Overture's, and
*OpenStreetMap or Overture* compares the two.

- **Data:**
  - **Places:** OpenStreetMap's Greater London extract, about 100 MB compressed from Geofabrik.
    From it, keep named places with a category (shops, venues, attractions, parks, schools) and
    named streets. That is likely 100,000–250,000 entries. Measure it before deciding.
  - **Postcodes:** OS Code-Point Open, under the Open Government Licence. That is roughly 200,000
    London postcode centers, which would replace TfL for postcodes as well.
  - **House numbers:** OpenStreetMap's coverage in London is patchy, so house-number search is the
    one gap this option can't close.
- **Size:** measured below: 13–25 MB raw, 3.6–8.2 MB added to the download (deflated) for the
  tiers worth considering. The raw size is roughly what the app holds in memory.
- **Cost:** £0 to run. The work is a build script and a weekly refresh job, beside the station
  list's.
- **Privacy:** nothing leaves the phone, so this is the best option on privacy. There's no Data
  Safety change and no extra TfL request.
- **Offline and speed:** works offline, and answers before the typing pause ends.
- **Licensing:**
  - OpenStreetMap is ODbL. The app has to credit "© OpenStreetMap contributors", and a derived
    database we distribute must itself be offered under ODbL.
  - Code-Point Open needs three statements: Ordnance Survey's Crown copyright and database right,
    Royal Mail's, and National Statistics' Crown copyright and database right.
- **Quality:** good for named places and streets, but **no popularity signal**. Without one,
  "Tate" can't tell Tate Modern from a corner shop. OpenStreetMap's Wikipedia and Wikidata links
  can stand in for popularity, which is what Nominatim does. Store it as an importance score per
  entry.
- **Engineering:** the current matcher scores one name at a time, which is too slow for
  hundreds of thousands of entries per keystroke. Two ways, both off the main thread (AGENTS.md
  *Main thread*):
  - **Scan compact arrays at run time, with nothing prebuilt.** Each name's folded key is held in
    one byte array and every search is one pass of byte comparisons. Nothing extra ships, so the
    sizes below are the whole cost; the price is reading the file at startup and a linear scan on
    each search.
  - **Prebuilt indexes**, shipped or built on the device, if the scan proves too slow on a phone.
    They cost download size or startup time that the sizes below don't include:
  - a sorted array or trie of normalized names with their spaces removed, for the prefix tier,
    holding every form the matcher treats as a prefix (each part of "City of Westminster, Tate
    Britain", and "Cross" as "X"), each pointing back to its entry. The matcher drops spaces
    from both the query and the name, so "tate m" has to find "tatemodern";
  - word starts, for the word-initials tier;
  - trigrams of the same space-free forms, for the substring tier, with a scan (or bigrams) for
    two-letter queries, which a trigram can't hold;
  - for the fuzzy tier, a list of names by each word's first letter, filtered by the existing
    matcher. Trigrams would lose its matches: "vctra" shares no three consecutive letters with
    "Victoria", yet matches it letter by letter in order.

### 5. Our own places server (OpenStreetMap, London only)

Run a search server ourselves over the same OpenStreetMap data:

- **Photon** (from komoot) is built for search as you type, tolerates typos, and ranks by
  importance. It imports from a Nominatim database or a Photon dump.
- **Nominatim** alone is the classic OpenStreetMap geocoder. It is better at resolving full
  addresses than at suggesting as you type.
- **Pelias** is capable, but heavier to run (several services plus Elasticsearch).

For London alone, Photon fits on one small virtual server: 2 CPUs and 4 GB of RAM, with an import
that takes minutes. That covers serving, not building, its index. Photon imports either from a Nominatim
database (Postgres plus Nominatim's import, which wants more memory and disk than serving does,
and is also what Photon's incremental updates need) or from a prepared JSON dump, which someone
else publishes on their own schedule. So the refresh is either a Nominatim build we run, on a bigger
machine for an hour or so each time, or a dependency on an outside dump.

- **Cost:** about €5–10 a month for the serving machine on a provider such as Hetzner, plus a
  domain, plus building its index: a bigger machine for an hour or so per refresh (around €1 a
  month, run weekly, if hired by the hour) or nothing extra with an outside dump, whose provider
  then has to keep publishing it. Scale-to-zero
  hosting (Cloud Run and the like) is a poor fit: the search index's cold start would land on the
  rider's first keystroke.
- **Ops:**
  - It's ours to keep up: updates, TLS, monitoring, and a weekly data refresh.
  - It needs abuse protection. A key built into the app isn't any: it can be pulled out of the
    APK and replayed, using up every real user's quota. What does work is limits per client or IP
    address (which shared networks and VPNs trip over), or Play Integrity attestation. Integrity
    is a Google request on each session: free, but with a default quota of 10,000 requests a day
    to raise with Google; Google collects app and device attestation data with each one, which
    goes in Data Safety; it costs a little battery and latency per session; and it fails on phones
    without Play services.
  - If it goes down, place results disappear. The stop results would still show, as with TfL's
    geocoder today.
- **Privacy:** the typed text and the IP address go to a server we control. That is better than a
  third party, but it is still a Data Safety entry, and `docs/PRIVACY.md` has to say what the
  server logs (ideally nothing).
- **Quality:** like option 4, plus whatever house numbers OpenStreetMap has, and Photon's
  importance ranking and typo tolerance out of the box.
- **When it beats option 4:** only if the bundled index turns out too big, or house-number search
  is wanted badly enough to justify running a service. Otherwise it is option 4 with an uptime
  problem added.

## Measured sizes (bundled index, 2026-10-11)

Measured from **Overture Maps** (release 2026-09-23) and **OS Code-Point Open**. Overture's
features are taken from a rectangle around Greater London (longitude −0.51 to 0.34, latitude 51.28
to 51.70), so they include a fringe outside the boundary (Watford, Dartford, Epsom). That is
deliberate (maintainer, 2026-10-11): TfL's services reach those towns, so riders head there. The
postcodes are taken from the same rectangle, so Watford's are found as its shops are.

### The sources

- **Overture places:** 494,598 places in London, each with a name, a category, a coordinate and a
  confidence score.
  - They come from Meta, Microsoft, Foursquare, AllThePlaces and several smaller providers
    (PinMeTo, Krick, RenderSEO, DAC, BrightQuery), not from OpenStreetMap.
  - Their licenses carry no share-alike condition, but they aren't all credit-only (Overture's
    [attribution table](https://docs.overturemaps.org/attribution/)): most are CDLA Permissive
    2.0 (credit); AllThePlaces is CC0 (nothing required); Foursquare's are Apache 2.0, so the app
    must ship the Apache license text, Foursquare's NOTICE file and its copyright line, and say
    the data was changed (transformed to Overture's schema, then filtered by us).
  - They're downloadable from Amazon S3, which this sandbox can reach.
- **Streets:** Overture's road network. Most of it is OpenStreetMap's (ODbL), but Overture also
  carries about 7,900 TomTom segments around London, which would need their own credit; the
  implementation keeps the OpenStreetMap ones only, as it does the neighborhoods. That gives
  51,507 distinct street names; keeping one entry per name per ~1 km cell gives 84,212 entries.
  Shown bare, those cells would list one street several times, and same-named streets in
  different areas identically, using up the result limit. So each street entry should carry its
  neighborhood ("High Street, Exampleton", from Overture's divisions data), with the cells of
  one name in one neighborhood collapsed to a single entry. As built, that's 75,546 entries,
  larger per entry than the bare cells because of the neighborhood names.
- **Postcodes:** OS Code-Point Open, the postcodes in the same rectangle: 213,011 (180,837 of them
  in the 33 London boroughs).
- **Street addresses:** Overture has no UK addresses. Measuring OpenStreetMap's London
  house-number coverage needs the Geofabrik extract, which this sandbox still can't reach.

### The tiers

An entry is name, category, latitude and longitude, stored as text sorted by name. "Deflate" is
roughly what the APK would add, since assets are zipped; "xz" is a lower bound if the build
precompresses the file. The rows marked "as built" are the real asset, whose lines also carry a kind
letter and a rank, so they take more bytes per entry than the measured rows above them. The
builder also cleans the places before writing them: it merges copies of one name within 250 m and
drops a landmark's stray copies far from it (the British Museum's, say). That is why the as-built A
and B rows hold fewer entries than their parts add up to: 748 fewer for A, 3,456 for B. The C row
is a plain sum of the measured rows, with no such cleaning.

| Tier | Entries | Raw | Deflate | xz |
|---|---:|---:|---:|---:|
| A. Public places: embassies, theaters, schools, parks, leisure centers, worship, museums, hospitals, venues… (confidence ≥ 0.7) | 50,374 | 2.0 MB | 0.8 MB | 0.6 MB |
| B. A + businesses with confidence ≥ 0.9 | 178,690 | 6.9 MB | 2.6 MB | 1.9 MB |
| C. All places, confidence ≥ 0.7 | 380,373 | 14.5 MB | 5.4 MB | 4.0 MB |
| D. All places | 494,598 | 18.8 MB | 7.1 MB | 5.3 MB |
| Streets, with neighborhoods (as built) | 75,553 | 3.8 MB | 1.1 MB | 0.7 MB |
| Postcodes (as built) | 213,011 | 6.6 MB | 1.7 MB | 0.9 MB |
| **A + streets + postcodes** (as built) | 338,190 | 13.4 MB | **3.6 MB** | 2.2 MB |
| **B + streets + postcodes** (as built) | 463,798 | 20.8 MB | **5.6 MB** | 3.6 MB |
| **C + streets + postcodes** (sum of the rows) | 668,937 | 24.9 MB | **8.2 MB** | 5.6 MB |

### Bus stops: replacing TfL's stop search too

The stations are already bundled (`app/src/main/assets/stations`). TfL's `/StopPoint/Search` is
mainly what finds **bus stops**, and TfL publishes them in bulk:

- **Source:** `/StopPoint/Mode/bus`, 33 pages and a few minutes of keyless requests a week from
  CI. It lists 32,582 records in London: stops, stop pairs and clusters. 30,950 of them list the
  routes they serve.
- **What they carry:** TfL's own IDs (the `490…` NaPTAN codes the app already opens), names,
  stop letters, positions, and the routes, which NaPTAN doesn't have.
- **Size:** about 2 MB raw, or **0.5 MB** deflated with each stop's routes.

With stations, bus stops, places, streets and postcodes all bundled, a *To…* search needs **no
TfL request at all**. It would answer on each keystroke, offline, with one ranking. TfL's
search would stay only as a fallback for anything the bundle can't find (a house number, a
brand-new stop), or could be dropped. One thing to check first is which IDs TfL's search
returns for a bus query (single stops or stop pairs), so the bundled rows open the same pages.

### Quality

A spot check on famous places finds the right one at confidence 0.94–1.0: Tate Modern, the
National Theatre, the embassies, the London Aquatics Centre, Westminster Abbey, the British
Museum. The data still needs work at build time:

- **Near-duplicates:** "The British Museum" and "British Museum London UK", or "Tate Modern" and
  "Tate Modern Museum". Merge the same category within ~100 m when one name contains the other.
- **Coarse categories:** the National Theatre is filed as a music venue, and the Aquatics Centre
  as a gym. The tier A list is broad on purpose because of this.
- **Low-confidence listings:** below 0.7, the listings are mostly small businesses with doubtful
  names or positions.

### Memory and search

- **Memory:** about the raw size, plus an index. Tier B with streets and postcodes is about
  21 MB of text, so it should be loaded off the main thread and kept as compact arrays, not
  objects.
- **Search speed:** a sorted-name prefix search is instant at this size. The trigram index for
  the substring tier, and the first-letter scan for the fuzzy tier, are the parts to benchmark
  on a low-end phone.

## OpenStreetMap or Overture for the bundled index?

The sizes above were measured from Overture, because the OpenStreetMap extract (Geofabrik) wasn't
reachable from the sandbox.

| | OpenStreetMap | Overture places |
|---|---|---|
| Public places (parks, schools, worship, embassies, theaters, leisure centers) | **Cleaner**: mapped by people, with precise positions | Many duplicates and mis-placed check-ins to clean up |
| Businesses | Thinner | **Broad** (Meta, Microsoft, Foursquare, AllThePlaces) |
| House numbers | Partial London coverage (to measure) | None for the UK |
| Ranking signal | Wikidata/Wikipedia links | **None**: its confidence score says how sure it is the place exists, isn't comparable across its sources, and so works only as a quality filter |
| License | ODbL: credit, **share-alike** for a distributed database (publishing the derived database itself, or the full method to rebuild it from the source, satisfies it) | CDLA Permissive 2.0 and CC0, plus Foursquare's Apache 2.0: credit, and the license and NOTICE shipped |
| Fixing errors | Upstream, by anyone | Not directly |

Overture's streets already come from OpenStreetMap, so the index carries an ODbL obligation either
way.

**Likely best: both.** OpenStreetMap for public places, streets and house numbers; Overture for
businesses (confidence ≥ 0.9). That changes only the build script; the on-device index and its
search stay as they are. It needs measuring once the extract is reachable.

## How each option fits the fuzzy search

### What the matcher does today

`StationMatcher` puts each name in one of four tiers, best first:

1. **Prefix:** the name starts with the query ("king" finds King's Cross).
2. **Anchored:** each letter starts a word or follows on from the last ("kx" finds King's X).
3. **Substring:** the query appears anywhere ("cross" finds Charing Cross).
4. **Fuzzy:** the first letter starts a word, and the rest follow in order ("vctra" finds Victoria).

Names are compared normalized (accents and punctuation dropped), and a name in parts ("Area,
Place") matches any part as a prefix.

Fuzzy here means **letters in order**, not edit distance, so a swapped or substituted letter misses.
"tate modren" doesn't find Tate Modern, because no "e" follows the "r".

### Remote engines (options 1, 3, 5)

The source ranks its results, and the app has to decide how far to trust that order.

- **TfL** ranks badly, so today's re-rank and filter stays right for TfL.
- **Google and Photon** rank well and tolerate typos. Re-ranking or filtering their results with
  our matcher would throw away right answers (the corrected typo above, or an address whose main
  text drops a word). So their order is kept. A result the matcher can place goes in among the
  stop rows by tier, and one it can't place goes after the stops, still listed. This is what the
  paused build does.
- **Stations** returned by these engines are dropped. The stop rows already list them, with the
  TfL IDs their departures need.
- **Results arrive after the typing pause**, so they're appended below what's already shown. They
  often land below a weaker stop match that showed first. That ranking quirk is the price of
  results arriving late, and a remote engine can't avoid it.

### Bundled index (option 4)

- **One ranking:** places and stops go through **one matcher and one ranking**, so a place can
  never rank oddly against a stop.
- **Rows stop arriving late:** everything is local and answers at once, so the "append, don't
  reorder" compromise mostly goes away for places.
- **Two things it needs to match a remote engine:**
  - **An importance tie-break** within a tier, so the famous place wins over the obscure one.
  - **A typo tier:** words within one edit of the query, below Fuzzy, to catch swapped and
    substituted letters. This would help stops too ("vicotria").

## Comparison

| | TfL (now) | Android Geocoder | Google Places | Bundled OSM | Own server |
|---|---|---|---|---|---|
| Place quality | Poor | Poor to fair | Best | Good, needs importance | Good |
| House numbers | Some | Some | Yes | Patchy | Patchy |
| Running cost | £0 | £0 | ~£0 at family scale, billing on | £0 | ~€6–11 a month |
| Typed text goes to | TfL | The device's geocoder (usually Google) | Google | Nowhere | Us |
| Data Safety change | No | Yes | Yes | No | Yes |
| Works offline | No | No | No | Yes | No |
| Fits our matcher | Re-ranked | Re-ranked | Kept in order | Natively | Kept in order |
| Keeping picks in *Recent* | Yes | Yes | Restricted | Yes | Yes |
| Ops and code burden | None | Low | Key and billing | Build script, index | A service to run |

## Suggested path

1. **Option 4 measured from Overture** (above): public places, streets and postcodes add about
   3.6 MB to the download; adding confident businesses brings it to about 5.6 MB. That's with the
   run-time scan, so nothing else ships; prebuilt indexes would add to it. Next, prototype tier A or B
   against the bundled station index's matcher, with the duplicate merging.
2. **Add the typo tier and the importance tie-break** to the matcher. Both help stops too.
3. **Bundle Code-Point Open for postcodes** if the size allows. On its own that drops TfL's
   second request only for postcode queries; together with the bundled places, it drops it for
   every query the bundle answers.
4. **Keep Google Places (the paused build) as the fallback** if option 4's quality falls short,
   and decide *Recent* under its terms then.
5. **Run our own server only** if house-number search turns out to matter, or the bundle is too
   big.
