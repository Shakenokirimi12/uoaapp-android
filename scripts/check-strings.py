#!/usr/bin/env python3
"""Checks the Android string resources.

Reports three things:
  1. R.string.x / @string/x references that strings.xml does not define
  2. strings.xml entries nothing references
  3. Japanese literals left in main Java / XML (log calls, comments, debug code and the skip list are not counted)

Exit status is 1 when (1) or (2) is not empty. (3) is informational: some literals must stay (parsing keys, persisted
values, files owned by the login / OTP / IdP refactor), see the notes printed next to them.

Usage: python3 scripts/check-strings.py
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'app', 'src')
MAIN = os.path.join(SRC, 'main')
JAVA_ROOT = os.path.join(MAIN, 'java', 'com', 'shakenokirimi12', 'uoa_app') + os.sep
RES_ROOT = os.path.join(MAIN, 'res') + os.sep

JP = re.compile('[぀-ヿ㐀-鿿＀-￯　-〿]')

# Owned by another refactor (login / OTP / IdP). Paths are relative to the Java package root or to res/.
SKIP_JAVA = [
    'services/idp/', 'services/otprelay/', 'services/SessionCoordinator.java', 'services/BackgroundLoginPolicy.java',
    'services/NetworkClient.java', 'services/AuthErrors.java', 'services/HostScopedCookieJar.java',
    'services/SpSessionCookies.java', 'services/CampusSquareService.java', 'services/MoodleService.java',
    'ui/settings/LoginSettingsFragment.java', 'ui/settings/AuthenticatorKeyPrompt.java', 'ui/idp/', 'ui/otprelay/',
    # Login-purpose files outside the explicit list (kept out when unsure).
    'ui/onboarding/OnboardingActivity.java',
]
SKIP_RES = [
    'layout/dialog_authenticator_key.xml', 'layout/dialog_edit_account.xml', 'layout/dialog_otp_prompt.xml',
    'layout/fragment_idp_otp_registration.xml', 'layout/fragment_idp_tutorial.xml',
    'layout/fragment_login_settings.xml', 'layout/fragment_otp_relay_settings.xml', 'layout/item_otp_relay_pairing.xml',
    'layout/page_onboarding_login.xml',
]
# Debug-only code is never converted.
DEBUG_MARKERS = ['/ui/debug/', '/src/debug/']

# Literals that stay on purpose: (relative path, substring of the literal) -> reason.
KEEP = [
    ('services/AppConfigService.java', 'この機能は現在利用できません',
     'FEATURE_DISABLED_MESSAGE is a String constant used by MoodleService / CampusSquareService (skip list)'),
    ('ui/facilities/FacilitiesFragment.java', '利用できません',
     'matches the text of FEATURE_DISABLED_MESSAGE to decide how to show an error (parsing, not display)'),
    ('services/attendance/AutoAbsencePlanner.java', '休講', 'matches CampusSquare event text'),
    ('services/attendance/AutoAbsencePlanner.java', 'オンライン', 'matches CampusSquare event text'),
    ('services/attendance/AutoAbsencePlanner.java', '遠隔', 'matches CampusSquare event text'),
    ('data/timetable/TimetableShareStore.java', '名前なし', 'stored as the friend name when the sender has none (persisted value, pinned by TimetableShareTest)'),
    ('services/GakushokuService.java', '', 'exception text that is never shown (callers show their own string); no Context in the service'),
    ('services/SyllabusService.java', '', 'error text that is never shown (CourseDetailFragment hides the card); no Context in the service'),
    ('services/diagnostics/OtpDiagnostics.java', '', 'diagnostic event text sent to Wana, not shown to the user'),
    ('res/navigation/nav_graph.xml', 'IdPログインについて', 'IdP destination (login refactor)'),
    ('res/navigation/nav_graph.xml', 'ワンタイムパスワード設定', 'IdP destination (login refactor)'),
]

# ---------------------------------------------------------------- collection


def read(path):
    with open(path, encoding='utf-8') as f:
        return f.read()


def walk(base, exts):
    for d, _, files in os.walk(base):
        for name in sorted(files):
            if name.endswith(exts):
                yield os.path.join(d, name)


def defined_strings():
    names = {}
    for path in walk(SRC, ('.xml',)):
        rel = os.path.relpath(path, SRC)
        parts = rel.split(os.sep)
        if 'res' not in parts or not parts[parts.index('res') + 1].startswith('values'):
            continue
        try:
            tree = ET.parse(path)
        except ET.ParseError as e:
            print('XML parse error in %s: %s' % (rel, e))
            sys.exit(2)
        for el in tree.getroot().iter('string'):
            names.setdefault(el.get('name'), rel)
    return names


def references():
    refs = {}
    # Only this app's R: a qualified `androidx.x.R.string.y` belongs to a library.
    java_re = re.compile(r'(?:(?<![\w.])|(?<=uoa_app\.))R\.string\.([A-Za-z0-9_]+)')
    xml_re = re.compile(r'@string/([A-Za-z0-9_]+)')
    for path in walk(SRC, ('.java', '.kt', '.xml')):
        rel = os.path.relpath(path, SRC)
        text = read(path)
        pat = java_re if path.endswith(('.java', '.kt')) else xml_re
        for m in pat.finditer(text):
            refs.setdefault(m.group(1), []).append(rel)
    return refs


# ---------------------------------------------------------------- Japanese literals


def java_literals(text):
    """Yield (line, literal) for every string literal outside comments and outside Log.* calls."""
    i, n, line = 0, len(text), 1
    stack = []  # callee names for open parentheses
    prev = ''   # last identifier chain before '('
    out = []
    while i < n:
        c = text[i]
        if c == '\n':
            line += 1
            i += 1
        elif text.startswith('//', i):
            while i < n and text[i] != '\n':
                i += 1
        elif text.startswith('/*', i):
            j = text.find('*/', i + 2)
            j = n if j < 0 else j + 2
            line += text.count('\n', i, j)
            i = j
        elif text.startswith('"""', i):
            j = text.find('"""', i + 3)
            j = n if j < 0 else j + 3
            lit = text[i:j]
            if not any(s.endswith(('Log.d', 'Log.i', 'Log.w', 'Log.e', 'Log.v', 'Log.wtf')) for s in stack):
                out.append((line, lit))
            line += text.count('\n', i, j)
            i = j
        elif c == '"':
            j = i + 1
            while j < n and text[j] != '"':
                j += 2 if text[j] == '\\' else 1
            lit = text[i:j + 1]
            if not any(s.endswith(('Log.d', 'Log.i', 'Log.w', 'Log.e', 'Log.v', 'Log.wtf')) for s in stack):
                out.append((line, lit))
            i = j + 1
        elif c == "'":
            j = i + 1
            while j < n and text[j] != "'":
                j += 2 if text[j] == '\\' else 1
            i = j + 1
        elif c == '(':
            m = re.search(r'([A-Za-z_][A-Za-z0-9_.]*)\s*$', text[max(0, i - 80):i])
            stack.append(m.group(1) if m else '')
            i += 1
        elif c == ')':
            if stack:
                stack.pop()
            i += 1
        else:
            i += 1
    return [(ln, lit) for ln, lit in out if JP.search(lit)]


def xml_literals(text):
    """Yield (line, attr, value) for attributes holding Japanese, outside comments and tools: attributes."""
    stripped = re.sub(r'<!--.*?-->', lambda m: re.sub(r'[^\n]', ' ', m.group(0)), text, flags=re.S)
    out = []
    for m in re.finditer(r'([A-Za-z_:][\w:.-]*)\s*=\s*"([^"]*)"', stripped):
        attr, value = m.group(1), m.group(2)
        if attr.startswith('tools:') or not JP.search(value):
            continue
        out.append((stripped.count('\n', 0, m.start()) + 1, attr, value))
    return out


def skipped(rel, skip):
    return any(rel == s or (s.endswith('/') and rel.startswith(s)) for s in skip)


def keep_reason(rel, literal):
    for path, sub, reason in KEEP:
        if rel == path and sub in literal:
            return reason
    return None


def japanese_leftovers():
    found, kept = [], []
    for path in walk(os.path.join(MAIN, 'java'), ('.java', '.kt')):
        rel = path[len(JAVA_ROOT):]
        if skipped(rel, SKIP_JAVA) or any(m in path for m in DEBUG_MARKERS):
            continue
        for line, lit in java_literals(read(path)):
            (kept if keep_reason(rel, lit) else found).append((rel, line, lit, keep_reason(rel, lit)))
    for path in walk(RES_ROOT, ('.xml',)):
        rel = path[len(RES_ROOT):]
        if rel.startswith('values') and os.path.basename(path).startswith('strings'):
            continue
        if skipped(rel, SKIP_RES):
            continue
        for line, attr, value in xml_literals(read(path)):
            key = 'res/' + rel
            reason = keep_reason(key, value)
            (kept if reason else found).append((key, line, '%s="%s"' % (attr, value), reason))
    path = os.path.join(MAIN, 'AndroidManifest.xml')
    for line, attr, value in xml_literals(read(path)):
        found.append(('AndroidManifest.xml', line, '%s="%s"' % (attr, value), None))
    return found, kept


# ---------------------------------------------------------------- main


def main():
    defined = defined_strings()
    refs = references()
    missing = sorted(k for k in refs if k not in defined)
    unused = sorted(k for k in defined if k not in refs)

    print('1. References missing from strings.xml: %d' % len(missing))
    for k in missing:
        print('   %s  (%s)' % (k, ', '.join(sorted(set(refs[k]))[:3])))

    print('2. strings.xml entries never referenced: %d' % len(unused))
    for k in unused:
        print('   %s  (%s)' % (k, defined[k]))

    found, kept = japanese_leftovers()
    print('3. Japanese literals left in non-skipped main sources: %d unexplained, %d kept on purpose' % (len(found), len(kept)))
    for rel, line, lit, _ in sorted(found):
        print('   %s:%d  %s' % (rel, line, lit if len(lit) < 100 else lit[:97] + '...'))
    if kept:
        print('   kept on purpose:')
        grouped = {}
        for rel, line, lit, reason in kept:
            grouped.setdefault((rel, reason), []).append(line)
        for (rel, reason), lines in sorted(grouped.items()):
            print('   - %s (lines %s): %s' % (rel, ', '.join(map(str, lines)), reason))
    print('Skipped (another refactor owns them):')
    for s in SKIP_JAVA + ['res/' + s for s in SKIP_RES]:
        print('   - %s' % s)
    return 1 if (missing or unused) else 0


if __name__ == '__main__':
    sys.exit(main())
