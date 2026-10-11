#!/usr/bin/env python3
"""Check the closure gate's publish verdicts against Maven's own effective poms (#1988, #1989).

`check-publish-closure.py` decides "published" by reading poms. This compares that verdict, for every reactor module, with what
Maven computes (profiles, inheritance, <inherited>false</inherited> and pluginManagement applied): a module publishes when the
Central plugin is bound in its effective <build><plugins> and its effective skipPublishing is not true.

    mvn -T 1 help:effective-pom -DperformRelease=true > effective.log
    python3 tools/check-publish-effective-pom.py effective.log

Exits 1 with one line per module whose two verdicts differ. Not part of CI (it needs a full reactor run); run it when the
plugin configuration or the inheritance of a parent changes.
"""
import re,sys,subprocess,json
import xml.etree.ElementTree as ET
import importlib.util
from pathlib import Path
spec=importlib.util.spec_from_file_location('cpc','tools/check-publish-closure.py'); m=importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
log=open(sys.argv[1]).read()
NS={'m':'http://maven.apache.org/POM/4.0.0'}
truth={}
chunks=[None]
for piece in log.split('Effective POM for project')[1:]:
    mm=re.search(r"'([^']+)'",piece)
    chunks.append(mm.group(1)); chunks.append(piece[mm.end():])
for i in range(1,len(chunks),2):
    name=chunks[i]; xml=chunks[i+1]
    start=xml.find('<project'); end=xml.find('</project>')+len('</project>')
    root=ET.fromstring(xml[start:end])
    g=root.findtext('m:groupId',namespaces=NS) or root.findtext('m:parent/m:groupId',namespaces=NS); a=root.findtext('m:artifactId',namespaces=NS)
    bound=None
    for p in root.findall('m:build/m:plugins/m:plugin',NS):
        if p.findtext('m:artifactId',namespaces=NS)=='central-publishing-maven-plugin': bound=p
    if bound is None: pub=False; skip='unbound'
    else:
        skip=bound.findtext('m:configuration/m:skipPublishing',namespaces=NS)
        pub = skip!='true'
    truth[(g,a)]=(pub,skip)
rows=m.decisions(Path('pom.xml'))
bad=0
for rel,g,a,pk,pub in rows:
    t=truth.get((g,a))
    if t is None or t[0]!=pub:
        bad+=1; print('MISMATCH',rel,g,a,'tool=',pub,'maven=',t)
print(len(rows),'modules, maven effective poms:',len(truth),'mismatches:',bad)
from collections import Counter
print(Counter(v[1] for v in truth.values()))

sys.exit(1 if bad else 0)
