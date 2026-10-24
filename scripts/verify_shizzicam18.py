#!/usr/bin/env python3
"""Verify actual Android identities, signing certificates and byte-identical embedded annex APKs."""
import argparse,hashlib,json,os,re,subprocess,zipfile
from pathlib import Path
def read_raw_resource(aapt, apk, name):
 table=subprocess.check_output([str(aapt),"dump","resources",str(apk)],text=True)
 # Release resource optimization can rename res/raw/name.mp3 to e.g. res/TB.mp3.
 # Resolve the runtime resource rather than assuming its ZIP entry retains its source name.
 pattern=r"(?m)^\s*resource\s+0x[0-9a-fA-F]+\s+(?:[^\s:]+:)?raw/"+re.escape(name)+r"[^\n]*\n\s*\(\)\s+\(file\)\s+(\S+)"
 matches=re.findall(pattern,table)
 assert len(matches)==1,(name,"expected one default raw resource",matches)
 with zipfile.ZipFile(apk) as archive:return matches[0],archive.read(matches[0])
def main():
 p=argparse.ArgumentParser()
 p.add_argument("--variant",choices=["debug","release"],default="release")
 p.add_argument("--output",default="shizzicam18-verification.json")
 args=p.parse_args()
 root=Path(__file__).resolve().parents[1]
 sdk=Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
 tools=sorted((sdk/"build-tools").iterdir(),key=lambda p:tuple(int(x) if x.isdigit() else 0 for x in p.name.split(".")))[-1]
 aapt=tools/"aapt2";signer=tools/"apksigner"
 permanent="FADFC5E87FA60351F7DCB75D34F1AB91110824333CD1097A38B3C703F7AF3DB7"
 rows=[
  ("app","dev.shizzi","0.4.3.5.18.2-test",50,None),
  ("conso","dev.shizzi.conso","0.3.5-relay18-test",25,"Shizzi-Plus.apk"),
  ("camadmin","dev.shizzi.camadmin","0.3.5-relay18-test",10,"ShizziCam-Admin.apk"),
  ("cammonitor","dev.shizzi.cammonitor","0.1.1-relay18-test",2,"ShizziCam-Debit.apk"),
  ("admin","dev.shizzi.admin","0.3.0-universal",15,"Shizzi-Admin.apk"),
  ("accounts","dev.shizzi.accounts","0.1.1",2,"Shizzi-Comptes.apk")]
 result={"baseCommit":"f4549ca970ce3e8cb827880e80b1ae223d113b99","variant":args.variant,
  "sourceCommit":subprocess.check_output(["git","rev-parse","HEAD"],cwd=root,text=True).strip(),
  "sourceBranch":subprocess.check_output(["git","branch","--show-current"],cwd=root,text=True).strip(),
  "permanentSignature":args.variant=="release","physicalInstallationVerified":False,
  "apks":[],"embeddedAnnexes":[]}
 archives={}
 router_cert=None
 distribution=(root/"app/src/main/java/dev/shizzi/ClientAppDistributionServer.kt").read_text()
 prefixes={"conso":"CLIENT","admin":"ADMIN","camadmin":"VIEWER","cammonitor":"MONITOR","accounts":"ACCOUNTS"}
 for module,pkg,version,code,asset in rows:
  path=root/module/"build/outputs/apk"/args.variant/(module+"-"+args.variant+".apk")
  assert path.is_file(),str(path)+" missing"
  data=path.read_bytes()
  with zipfile.ZipFile(path) as z:
   assert z.testzip() is None
   assert "AndroidManifest.xml" in z.namelist() and "classes.dex" in z.namelist()
  badge=subprocess.check_output([str(aapt),"dump","badging",str(path)],text=True)
  identity=re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",badge)
  assert identity and identity.groups()==(pkg,str(code),version),(module,identity.groups() if identity else badge[:200])
  assert re.search(r"(?:minSdkVersion|sdkVersion):\s*'30'",badge),(module,"Android 11 minimum missing")
  cert_output=subprocess.check_output(["bash",str(signer),"verify","--print-certs",str(path)],text=True)
  cert=re.search(r"certificate SHA-256 digest: ([a-fA-F0-9]+)",cert_output).group(1).upper()
  if module=="app":router_cert=cert
  assert cert==router_cert, module+" differs from router signature"
  if args.variant=="release":assert cert==permanent,module+" permanent signing certificate differs"
  entry={"module":module,"package":pkg,"version":version,"versionCode":code,"sizeBytes":len(data),
   "sha256":hashlib.sha256(data).hexdigest(),"certificateSha256":cert}
  if asset:
   prefix=prefixes[module]
   advertised=re.search(r'const val '+prefix+r'_VERSION = "([^"]+)"',distribution)
   assert advertised and advertised.group(1)==version,(module,"portal advertises a different APK version")
   asset_name="ASSET_PATH" if module=="conso" else prefix+"_ASSET_PATH"
   asset_path=re.search(r'const val '+asset_name+r' = "([^"]+)"',distribution)
   assert asset_path and asset_path.group(1)=="shizzi/"+asset,(module,"portal uses a different embedded asset")
   entry["advertisedVersion"]=advertised.group(1)
  result["apks"].append(entry);archives[module]=path
 siren_path,siren=read_raw_resource(aapt,archives["conso"],"shizzicam_siren")
 assert siren==(root/"conso/src/main/res/raw/shizzicam_siren.mp3").read_bytes(),"embedded siren differs"
 result["sirenResourcePath"]=siren_path
 result["sirenSha256"]=hashlib.sha256(siren).hexdigest()
 with zipfile.ZipFile(archives["app"]) as router:
  for module,pkg,version,code,asset in rows[1:]:
   embedded=router.read("assets/shizzi/"+asset)
   assert embedded==archives[module].read_bytes(),"embedded "+asset+" differs"
   result["embeddedAnnexes"].append({"asset":asset,"sizeBytes":len(embedded),"sha256":hashlib.sha256(embedded).hexdigest()})
 Path(args.output).write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n")
 print("Verified",len(rows),"APK identities and",len(rows)-1,"embedded annexes;",args.variant,
       "permanent signing" if args.variant=="release" else "diagnostic signing only")
if __name__=="__main__":main()
