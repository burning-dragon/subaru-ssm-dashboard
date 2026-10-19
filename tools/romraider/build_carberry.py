"""Build the isolated CarBerry 4.2 logger profile; no ROM/editor definitions.

Source: Crowley2012/SubaruTuning, Definitions/Logger/Carberry-SDlogger-rev1.9.xml.
Keep the downloaded source gzipped beside this script for reproducible offline builds.
"""
import gzip
import hashlib
import json
import sys
from pathlib import Path
import xml.etree.ElementTree as ET
sys.dont_write_bytecode = True
from build_defs import address, conversions

HERE = Path(__file__).resolve().parent
ASSETS = HERE.parents[1] / 'app/src/main/assets'
ECU = '3D04EA4605'
source = gzip.decompress((HERE / 'Carberry-SDlogger-rev1.9.xml.gz').read_bytes())
ssm = next(p for p in ET.fromstring(source).iter('protocol') if p.get('id') == 'SSM')
stock = json.loads((ASSETS / 'romraider_ssm.json').read_text(encoding='utf-8'))
common = {p['id']: p for p in stock['ecuparams'] if ECU in p['r']}
params = []
for p in ssm.find('ecuparams'):
    ecu = next((e for e in p.findall('ecu') if ECU in e.get('id', '').split(',')), None)
    if ecu is None:
        continue
    original = p.get('id')
    a = address(ecu.find('address'))
    old = common.get(original)
    if old:
        assert old['r'][ECU] == a, original
    # Shared channels keep existing metric/lambda units used by EngineGuard.
    # New channels have isolated IDs, never colliding with stock E153 etc.
    params.append(dict(id=original if old else 'CB42_' + original, sourceId=original,
                       t=1, n=p.get('name').strip().rstrip('*').strip(),
                       ru=old.get('ru', '') if old else '',
                       d=p.get('desc', ''), a=a,
                       c=old['c'] if old else conversions(p)))
assert len(params) == 67
result = dict(profile='carberry42', ecuId=ECU,
              source='CarBerry SDlogger rev1.9; RomRaider logger definitions',
              url='https://github.com/Crowley2012/SubaruTuning/blob/master/Definitions/Logger/Carberry-SDlogger-rev1.9.xml',
              sha256=hashlib.sha256(source).hexdigest(), params=params)
(ASSETS / 'carberry42_logger.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print('CarBerry 4.2:', len(params), 'extended channels')
