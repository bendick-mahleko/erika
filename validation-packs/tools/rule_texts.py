#!/usr/bin/env python3
"""
Builds the German rule-text catalogue of a validation pack (VW-04, ADR 0021).

    python validation-packs/tools/rule_texts.py <assembled pack dir> <pack source dir>

  <assembled pack dir>  backend/build/validation-packs/<id>  (after ./gradlew prepareValidationPacks)
  <pack source dir>     validation-packs/<id>                (rule-texts.de.json is written here)

Every rule ID in the pack's compiled Schematron gets a German text from, in this order:
  1. validation-packs/rule-texts/de.json  hand-written texts (pack-independent; review these)
  2. templates below for the VAT-category rules (BR-S/Z/E/AE/IC/G/O/AF/AG-nn) and BR-DEC-nn
  3. patterns for the purely syntactic rules (UBL-CR, UBL-SR, UBL-DT, CII-SR, CII-DT):
     "element not intended by EN 16931", "at most once", "exactly once"
The script fails if any rule ID is left without a text, so a new pack can't silently
ship untranslated rules. The backend test RuleTextCatalogueTest checks the same.
"""
import json
import re
import sys
from pathlib import Path

ASSEMBLED, SOURCE = Path(sys.argv[1]), Path(sys.argv[2])
CURATED = Path(__file__).resolve().parent.parent / "rule-texts" / "de.json"


def schematron_rules(pack_dir: Path) -> dict:
    rules = {}
    for xsl in sorted(pack_dir.glob("**/xsl/*.xsl")):
        text = xsl.read_text(encoding="utf-8")
        for m in re.finditer(r"<svrl:(failed-assert|successful-report)\b([^>]*)>(.*?)</svrl:\1>", text, re.S):
            rid = re.search(r'\bid="([^"]+)"', m.group(2))
            flag = re.search(r'\bflag="([^"]+)"', m.group(2))
            msg = re.search(r"<svrl:text>(.*?)</svrl:text>", m.group(3), re.S)
            if rid and msg:
                body = re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", msg.group(1))).strip()
                body = re.sub(r"^\[[^\]]+\]\s*-?\s*", "", body)
                rules.setdefault(rid.group(1), (flag.group(1) if flag else "fatal", body))
    return rules


# --- VAT categories -----------------------------------------------------------
CATS = {
    "S": ("Normalsatz", "S", "mindestens eine"),
    "Z": ("Nullsatz", "Z", "genau eine"),
    "E": ("steuerbefreit", "E", "genau eine"),
    "AE": ("Steuerschuldnerschaft des Leistungsempfängers (Reverse Charge)", "AE", "genau eine"),
    "IC": ("innergemeinschaftliche Lieferung", "K", "genau eine"),
    "G": ("Ausfuhr außerhalb der EU", "G", "genau eine"),
    "O": ("nicht steuerbar", "O", "genau eine"),
    "AF": ("IGIC – Kanarische Inseln", "L", "mindestens eine"),
    "AG": ("IPSI – Ceuta und Melilla", "M", "mindestens eine"),
}
SUBJECTS = {
    2: ("Rechnungsposition", "BT-151"), 3: ("Nachlass auf Rechnungsebene", "BT-95"), 4: ("Zuschlag auf Rechnungsebene", "BT-102"),
    5: ("Rechnungsposition", "BT-152"), 6: ("Nachlass auf Rechnungsebene", "BT-96"), 7: ("Zuschlag auf Rechnungsebene", "BT-103"),
}
SELLER_IDS = {
    "default": "Die USt-IdNr. des Verkäufers, seine Steuernummer oder die USt-IdNr. seines Steuervertreters muss angegeben sein (BT-31, BT-32, BT-63).",
    "AE": "Die USt-IdNr. oder Steuernummer des Verkäufers (oder seines Steuervertreters) und die USt-IdNr. oder Registernummer des Käufers müssen angegeben sein (BT-31, BT-32, BT-63, BT-48, BT-47).",
    "IC": "Die USt-IdNr. des Verkäufers (oder seines Steuervertreters) und die USt-IdNr. des Käufers müssen angegeben sein (BT-31, BT-63, BT-48).",
    "G": "Die USt-IdNr. des Verkäufers oder seines Steuervertreters muss angegeben sein (BT-31, BT-63).",
    "O": "Weder die USt-IdNr. des Verkäufers noch die seines Steuervertreters oder des Käufers darf angegeben sein (BT-31, BT-63, BT-48).",
}


def vat_text(cat: str, n: int):
    name, code, how_many = CATS[cat]
    c = f"„{name}“ ({code})"
    with_rate = cat in ("S", "AF", "AG")
    if n == 1:
        return f"Enthält die Rechnung Beträge der Umsatzsteuerkategorie {c}, muss die Umsatzsteueraufschlüsselung {how_many} Zeile mit dieser Kategorie enthalten (BG-23, BT-118)."
    if n in (2, 3, 4):
        subject, bt = SUBJECTS[n]
        return f"{subject} mit Umsatzsteuerkategorie {c}: {SELLER_IDS.get(cat, SELLER_IDS['default'])}"
    if n in (5, 6, 7):
        subject, bt = SUBJECTS[n]
        if cat == "O":
            return f"{subject} mit Umsatzsteuerkategorie {c}: Es darf kein Steuersatz angegeben sein ({bt})."
        if cat == "S" or (cat == "AF" and n == 5):
            rule = "größer als 0 % sein"
        elif cat in ("AF", "AG"):
            rule = "0 % oder größer sein"
        else:
            rule = "0 % sein"
        return f"{subject} mit Umsatzsteuerkategorie {c}: Der Steuersatz muss {rule} ({bt})."
    if n == 8:
        if with_rate:
            return f"Für jeden Steuersatz der Kategorie {c} muss der steuerpflichtige Betrag der Summe der Positionsbeträge plus Zuschläge minus Nachlässe mit diesem Steuersatz entsprechen (BT-116)."
        return f"Der steuerpflichtige Betrag der Kategorie {c} muss der Summe der Positionsbeträge minus Nachlässe plus Zuschläge dieser Kategorie entsprechen (BT-116)."
    if n == 9:
        if with_rate:
            return f"Der Steuerbetrag der Kategorie {c} muss dem steuerpflichtigen Betrag mal Steuersatz entsprechen (BT-117, BT-116, BT-119)."
        return f"Der Steuerbetrag der Kategorie {c} muss 0 sein (BT-117)."
    if n == 10:
        if cat in ("S", "Z", "AF", "AG"):
            return f"Die Umsatzsteueraufschlüsselung der Kategorie {c} darf keinen Grund für eine Steuerbefreiung enthalten (BT-120, BT-121)."
        if cat == "E":
            return f"Die Umsatzsteueraufschlüsselung der Kategorie {c} muss den Grund der Steuerbefreiung angeben, als Text oder Code (BT-120, BT-121)."
        return f"Die Umsatzsteueraufschlüsselung der Kategorie {c} muss den Befreiungsgrund „{name}“ als Code oder Text angeben (BT-120, BT-121)."
    extra = {
        ("IC", 11): "Bei einer innergemeinschaftlichen Lieferung muss das Lieferdatum oder der Abrechnungszeitraum angegeben sein (BT-72, BG-14).",
        ("IC", 12): "Bei einer innergemeinschaftlichen Lieferung muss das Land der Lieferanschrift angegeben sein (BT-80).",
        ("O", 11): "Enthält die Rechnung nicht steuerbare Umsätze (Kategorie O), darf die Umsatzsteueraufschlüsselung keine weiteren Kategorien enthalten (BG-23).",
        ("O", 12): "Enthält die Rechnung nicht steuerbare Umsätze (Kategorie O), müssen alle Positionen dieser Kategorie angehören (BT-151).",
        ("O", 13): "Enthält die Rechnung nicht steuerbare Umsätze (Kategorie O), müssen alle Nachlässe auf Rechnungsebene dieser Kategorie angehören (BT-95).",
        ("O", 14): "Enthält die Rechnung nicht steuerbare Umsätze (Kategorie O), müssen alle Zuschläge auf Rechnungsebene dieser Kategorie angehören (BT-102).",
    }
    return extra.get((cat, n))


# --- Decimal rules --------------------------------------------------------------
AMOUNTS = {
    "BT-92": "Nachlass auf Rechnungsebene", "BT-93": "Grundbetrag des Nachlasses auf Rechnungsebene",
    "BT-99": "Zuschlag auf Rechnungsebene", "BT-100": "Grundbetrag des Zuschlags auf Rechnungsebene",
    "BT-106": "Summe der Positionsbeträge", "BT-107": "Summe der Nachlässe auf Rechnungsebene",
    "BT-108": "Summe der Zuschläge auf Rechnungsebene", "BT-109": "Gesamtbetrag ohne Umsatzsteuer",
    "BT-110": "Umsatzsteuer-Gesamtbetrag", "BT-111": "Umsatzsteuer-Gesamtbetrag in Steuerwährung",
    "BT-112": "Gesamtbetrag mit Umsatzsteuer", "BT-113": "bereits gezahlter Betrag", "BT-114": "Rundungsbetrag",
    "BT-115": "fälliger Betrag", "BT-116": "steuerpflichtiger Betrag je Kategorie", "BT-117": "Steuerbetrag je Kategorie",
    "BT-131": "Nettobetrag der Position", "BT-136": "Nachlass auf die Position", "BT-137": "Grundbetrag des Nachlasses auf die Position",
    "BT-141": "Zuschlag auf die Position", "BT-142": "Grundbetrag des Zuschlags auf die Position",
}


def decimal_text(english: str):
    bt = re.search(r"\((BT-\d+)\)", english)
    if bt and bt.group(1) in AMOUNTS:
        return f"Der Betrag „{AMOUNTS[bt.group(1)]}“ darf höchstens zwei Nachkommastellen haben ({bt.group(1)})."
    return None


# --- Syntax rules ---------------------------------------------------------------
def syntax_text(flag: str, english: str):
    should = flag != "fatal"
    m = re.match(r"^A UBL (?:invoice|credit note) (?:should|shall) not include (?:the |an? )?(.+?)\.?$", english, re.I) \
        or re.match(r"^(.+?) ?should ?not be present\.?$", english, re.I) \
        or re.match(r"^(.+?) shall not be used\.?$", english, re.I)
    if m:
        el = m.group(1).strip()
        if should:
            return f"Die Rechnung enthält das Element „{el}“, das die EN 16931 nicht vorsieht. Es wird bei der Verarbeitung nicht berücksichtigt."
        return f"Die Rechnung enthält das Element „{el}“, das in einer Rechnung nach EN 16931 nicht verwendet werden darf."
    m = re.match(r"^(.+?) (?:shall|should|must|may) (?:occur|exist|be present) (?:maximum|at most|no more than) once(?: in (?:the document|([^\s.]+)))?\.?$", english, re.I)
    if m:
        where = f" in „{m.group(2)}“" if m.group(2) else ""
        return f"Das Element „{m.group(1).strip()}“ {'sollte' if should else 'darf'}{where} höchstens einmal vorkommen."
    m = re.match(r"^(.+?) must exist exactly once\.?$", english, re.I)
    if m:
        return f"Das Element „{m.group(1).strip()}“ muss genau einmal vorkommen."
    m = re.match(r"^Only one (.+?) (?:should|shall) be present(.*)$", english, re.I)
    if m:
        return f"Es {'sollte' if should else 'darf'} nur ein Element „{m.group(1).strip()}“ angegeben sein."
    return None


def main():
    rules = schematron_rules(ASSEMBLED)
    curated = json.loads(CURATED.read_text(encoding="utf-8"))
    texts, sources, missing = {}, {"curated": 0, "vat": 0, "decimal": 0, "syntax": 0}, []
    for rid, (flag, english) in sorted(rules.items()):
        vat = re.fullmatch(r"BR-(S|Z|E|AE|IC|G|O|AF|AG)-(\d+)", rid)
        if rid in curated:
            texts[rid], kind = curated[rid], "curated"
        elif vat and (t := vat_text(vat.group(1), int(vat.group(2)))):
            texts[rid], kind = t, "vat"
        elif rid.startswith("BR-DEC-") and (t := decimal_text(english)):
            texts[rid], kind = t, "decimal"
        elif re.match(r"(UBL|CII)-(CR|SR|DT)-", rid) and (t := syntax_text(flag, english)):
            texts[rid], kind = t, "syntax"
        else:
            missing.append(f"{rid} ({flag}): {english}")
            continue
        sources[kind] += 1
    unused = sorted(set(curated) - set(rules))
    out = SOURCE / "rule-texts.de.json"
    out.write_text(json.dumps(texts, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8", newline="\n")
    print(f"{len(rules)} rule IDs, {len(texts)} texts written to {out}: {sources}")
    if unused:
        print(f"Note: {len(unused)} hand-written texts not used by this pack: {', '.join(unused)}")
    if missing:
        print(f"{len(missing)} rule IDs without a German text:\n  " + "\n  ".join(missing))
        sys.exit(1)


main()
