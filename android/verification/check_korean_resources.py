"""Verify Korean coverage and Android format arguments without extra dependencies."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

resources = Path(__file__).resolve().parents[1] / 'app/src/main/res'
format_argument = re.compile(r'%(?!%)(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]')


def read_strings(directory):
    values = {}
    for file in sorted(directory.glob('*.xml')):
        for node in ET.parse(file).getroot().findall('string'):
            name = node.attrib['name']
            assert name not in values, f'Duplicate string: {name}'
            values[name] = (''.join(node.itertext()), node.attrib.get('translatable') != 'false')
    return values


def arguments(text):
    # A literal escaped percent must never become a format argument.
    return sorted(format_argument.findall(text.replace('%%', '')))


english = read_strings(resources / 'values')
korean = read_strings(resources / 'values-ko')
expected = {name for name, (_, translated) in english.items() if translated}
missing = expected - korean.keys()
assert not missing, f'Missing Korean keys: {sorted(missing)}'
for name in expected:
    assert arguments(english[name][0]) == arguments(korean[name][0]), f'Format mismatch: {name}'
assert 'app_name' not in korean, 'The branded personal app name must remain unchanged'
for name in ('ko_runtime_loading', 'ko_runtime_phone', 'ko_ui_flavor_label', 'transparency'):
    assert re.search('[가-힣]', korean[name][0]), f'Expected Korean UI label: {name}'
print(f'PASS: {len(expected)} Korean strings covered; format arguments match; app name preserved')
unchanged = [name for name in expected if korean[name][0] == english[name][0]]
print('Unchanged proper names/identifiers for review: ' + ', '.join(sorted(unchanged)))
