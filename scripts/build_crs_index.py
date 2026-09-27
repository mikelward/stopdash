#!/usr/bin/env python3
"""Build LDN Go's bundled National Rail station codes from NaPTAN (SPEC *National Rail*).

Writes the JSON the app reads as `assets/stations/crs_codes.json`: for every active rail
station in NaPTAN (the DfT's stop database, area 910), its railway location code (TIPLOC) and
its three-letter National Rail code (CRS). The app looks up National Rail departures by CRS,
while TfL names the same station `910G` + TIPLOC (`910GWATRLMN`) and NaPTAN `9100` + TIPLOC
(`9100WATRLMN`), so the two join exactly on the TIPLOC, with no matching by name or position.

Only the XML export carries the CRS (`<CrsRef>`); the CSV doesn't. A station with no CRS (a
rail-replacement bus stop, a closed station) is left out, and so the app shows no National
Rail times there. A failed download or a short list (under MIN_CODES) stops the build, so the
committed table stays rather than a partial one replacing it. Run by the `station-index-refresh`
workflow; the sandboxed dev environment may not reach NaPTAN.

NaPTAN is Crown copyright, published under the Open Government Licence v3.0; the app credits
it in its About dialog. Public transport data only — nothing about any user.
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

URL = "https://naptan.api.dft.gov.uk/v1/access-nodes?dataFormat=xml&atcoAreaCodes=910"
ATCO_PREFIX = "9100"
MIN_CODES = 2000
FORMAT_VERSION = 1
# Codes the departure-board service refuses, each with the code whose board carries its trains:
# St Pancras low level (SPL) answers HTTP 400, and its Thameslink trains come on St Pancras's own
# board (STP). So a train boarding there is timed from STP's board, never a failing request.
BOARD_OF = {"SPL": "STP"}
RETRY_DELAYS = (5, 15, 45)


def fetch(url=URL, retry_delays=RETRY_DELAYS):
    """GET NaPTAN's rail XML as bytes, retrying a timeout or a 5xx."""
    request = urllib.request.Request(url, headers={"User-Agent": "stopdash-crs-index"})
    for attempt, delay in enumerate((0,) + tuple(retry_delays)):
        if delay:
            print(f"retrying NaPTAN in {delay}s", file=sys.stderr)
            time.sleep(delay)
        try:
            with urllib.request.urlopen(request, timeout=300) as response:
                return response.read()
        except urllib.error.HTTPError as e:
            if e.code < 500 or attempt == len(retry_delays):
                raise
        except (urllib.error.URLError, TimeoutError):
            if attempt == len(retry_delays):
                raise


def local(tag):
    """An element tag without its namespace."""
    return tag.rsplit("}", 1)[-1]


def parse(xml_bytes):
    """TIPLOC -> CRS for each active NaPTAN rail station that has a CRS."""
    codes = {}
    for _, element in _iterparse(xml_bytes):
        if local(element.tag) != "StopPoint":
            continue
        if element.get("Status", "active") != "active":
            element.clear()
            continue
        atco = next((e.text for e in element.iter() if local(e.tag) == "AtcoCode"), None) or ""
        crs = next((e.text for e in element.iter() if local(e.tag) == "CrsRef"), None)
        if atco.startswith(ATCO_PREFIX) and crs and len(crs.strip()) == 3:
            codes[atco[len(ATCO_PREFIX):]] = crs.strip().upper()
        element.clear()
    return codes


def _iterparse(xml_bytes):
    import io
    return ET.iterparse(io.BytesIO(xml_bytes), events=("end",))


def one_per_crs(codes, stations=None):
    """Drops the TIPLOCs of a station TfL lists under several ids that aren't its National Rail
    one (an Overground-only id beside a National Rail one), so that station's board shows under
    its National Rail stop, not twice. Kept: every id TfL's station list
    ([stations], id -> modes) gives the national-rail mode; else every id in that list; else the
    first alphabetically (a station outside it, which the app doesn't show). Two National Rail ids
    for one CRS (St Pancras) both stay: the app shares one board between them."""
    stations = stations or {}
    by_crs = {}
    for tiploc, crs in codes.items():
        by_crs.setdefault(crs, []).append(tiploc)
    kept = {}
    for crs, tiplocs in by_crs.items():
        rail = [t for t in tiplocs if "national-rail" in stations.get("910G" + t, [])]
        listed = [t for t in tiplocs if "910G" + t in stations]
        for tiploc in rail or listed or [min(tiplocs)]:
            kept[tiploc] = crs
    return kept


def read_station_modes(path):
    """The bundled station index's TfL id -> modes, for [one_per_crs]."""
    with open(path, encoding="utf-8") as f:
        return {s["id"]: s.get("modes", []) for s in json.load(f)["stations"]}


def build(xml_bytes, stations=None):
    codes = {t: BOARD_OF.get(c, c) for t, c in one_per_crs(parse(xml_bytes), stations).items()}
    if len(codes) < MIN_CODES:
        raise SystemExit(f"only {len(codes)} station codes (want at least {MIN_CODES}); not writing")
    return {
        "version": FORMAT_VERSION,
        "source": "NaPTAN (Department for Transport), Open Government Licence v3.0",
        "codes": dict(sorted(codes.items())),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", required=True)
    parser.add_argument("--xml", help="read NaPTAN XML from this file instead of downloading it")
    parser.add_argument("--stations", help="the station index, to pick one TfL id per CRS")
    args = parser.parse_args()
    if args.xml:
        with open(args.xml, "rb") as f:
            data = f.read()
    else:
        data = fetch()
    table = build(data, read_station_modes(args.stations) if args.stations else None)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(table, f, indent=1, ensure_ascii=False)
        f.write("\n")
    print(f"wrote {len(table['codes'])} station codes to {args.out}")


if __name__ == "__main__":
    main()
