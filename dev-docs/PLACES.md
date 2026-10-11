# Searching for places

**Status: design options, nothing decided.** This is a developer-facing write-up of how the *To…*
search could find arbitrary destinations (a landmark, a shop, an address, a street) better than it
does today. It is not a commitment. Each option changes cost, privacy and Play Data Safety, so the
choice is the maintainer's.

## Where it stands

- *To…* already offers places: it sends the typed text to **TfL's Journey Planner**, which resolves
  it to candidate locations (SPEC *To… a place or postcode*).
- TfL's results are poor for places. A well-known landmark can be missing or ranked low, and the
  results arrive in a noisy order. The app re-ranks them with its own name matcher and drops any
  whose name doesn't match.
- Postcodes work well through the same call, and stay tagged *Postcode*.
- Each *To…* search costs a second TfL request for this (SPEC *Cost*), on top of the stop search.

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
- **Size:** about 30 bytes an entry (name, coordinate, category, importance) gives roughly 3–8 MB
  before compression. That needs measuring, and the store size limit matters.
- **Cost:** £0 to run. The work is a build script and a weekly refresh job, beside the station
  list's.
- **Privacy:** nothing leaves the phone, so this is the best option on privacy. There's no Data
  Safety change and no extra TfL request.
- **Offline and speed:** works offline, and answers before the typing pause ends.
- **Licensing:**
  - OpenStreetMap is ODbL. The app has to credit "© OpenStreetMap contributors", and a derived
    database we distribute must itself be offered under ODbL.
  - Code-Point Open needs the Crown copyright and Royal Mail notices.
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
  - a sorted array or trie of normalized names, for the prefix tier;
  - word starts, for the word-initials tier;
  - trigrams, for the substring tier;
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
that takes minutes.

- **Cost:** about €5–10 a month on a provider such as Hetzner, plus a domain. Scale-to-zero
  hosting (Cloud Run and the like) is a poor fit: the search index's cold start would land on the
  rider's first keystroke.
- **Ops:**
  - It's ours to keep up: updates, TLS, monitoring, and a weekly data refresh.
  - It needs abuse protection. A key built into the app isn't any: it can be pulled out of the
    APK and replayed, using up every real user's quota. What does work is limits per client or IP
    address (which shared networks and VPNs trip over), or Play Integrity attestation (which adds
    a Google dependency, a check on each session, and fails on phones without Play services).
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
| Running cost | £0 | £0 | ~£0 at family scale, billing on | £0 | ~€5–10 a month |
| Typed text goes to | TfL | The device's geocoder (usually Google) | Google | Nowhere | Us |
| Data Safety change | No | Yes | Yes | No | Yes |
| Works offline | No | No | No | Yes | No |
| Fits our matcher | Re-ranked | Re-ranked | Kept in order | Natively | Kept in order |
| Keeping picks in *Recent* | Yes | Yes | Restricted | Yes | Yes |
| Ops and code burden | None | Low | Key and billing | Build script, index | A service to run |

## Suggested path

1. **Measure option 4 first.** Extract London's named places and streets with an importance score,
   and record the count and compressed size. If it fits in a few MB, prototype it against the
   bundled station index's matcher.
2. **Add the typo tier and the importance tie-break** to the matcher. Both help stops too.
3. **Bundle Code-Point Open for postcodes** if the size allows. On its own that drops TfL's
   second request only for postcode queries; together with the bundled places, it drops it for
   every query the bundle answers.
4. **Keep Google Places (the paused build) as the fallback** if option 4's quality falls short,
   and decide *Recent* under its terms then.
5. **Run our own server only** if house-number search turns out to matter, or the bundle is too
   big.
