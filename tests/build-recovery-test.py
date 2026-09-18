from pathlib import Path
import subprocess,zipfile
root=Path.cwd();test=root/'work/recovery-test';sdk=root/'work/toolchain/android-sdk';build=sdk/'build-tools/35.0.0';jdk=next((root/'work/toolchain').glob('jdk-*'))/'Contents/Home/bin';android=sdk/'platforms/android-36/android.jar'
def run(args):subprocess.run([str(a) for a in args],check=True)
(test/'classes').mkdir(exist_ok=True);(test/'dex').mkdir(exist_ok=True)
run([jdk/'javac','-source','8','-target','8','-cp',android,'-d',test/'classes',test/'src/dev/airtv/tests/RecoveryTest.java'])
run([jdk/'java','-cp',build/'lib/d8.jar','com.android.tools.r8.D8','--min-api','24','--lib',android,'--output',test/'dex',*list((test/'classes').rglob('*.class'))])
run([build/'aapt2','link','-I',android,'--manifest',test/'AndroidManifest.xml','-o',test/'unsigned.apk'])
with zipfile.ZipFile(test/'unsigned.apk','a') as z:z.write(test/'dex/classes.dex','classes.dex')
run([build/'zipalign','-f','4',test/'unsigned.apk',test/'aligned.apk'])
run([jdk/'java','-jar',build/'lib/apksigner.jar','sign','--ks',root/'work/airtv-debug.keystore','--ks-pass','pass:android','--key-pass','pass:android','--out',test/'recovery-test.apk',test/'aligned.apk'])
