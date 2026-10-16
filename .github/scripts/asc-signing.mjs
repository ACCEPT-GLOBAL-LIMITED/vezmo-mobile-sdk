// App Store signing for CI through the App Store Connect API: a temporary distribution certificate and App Store
// profile for one build, deleted afterwards (no device or stored certificate needed).
//
//   node asc-signing.mjs setup <bundleId> <workDir>   → key.pem, cert.cer, profile.mobileprovision; prints PROFILE_*
//   node asc-signing.mjs cleanup <workDir>            → revokes the certificate, deletes the profile
//   node asc-signing.mjs testflight <bundleId>        → the latest builds' state; a ready build joins the internal groups
//
// Env: ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH (the .p8). Only names and ids are printed, never key material.
import { execFileSync } from 'node:child_process';
import { createPrivateKey, sign } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const API = 'https://api.appstoreconnect.apple.com/v1';
const b64url = (b) => Buffer.from(b).toString('base64url');

function token() {
  const key = createPrivateKey(readFileSync(process.env.ASC_KEY_PATH, 'utf8'));
  const now = Math.floor(Date.now() / 1000);
  const head = b64url(JSON.stringify({ alg: 'ES256', kid: process.env.ASC_KEY_ID, typ: 'JWT' }));
  const body = b64url(JSON.stringify({ iss: process.env.ASC_ISSUER_ID, iat: now, exp: now + 1200, aud: 'appstoreconnect-v1' }));
  const sig = sign('sha256', Buffer.from(`${head}.${body}`), { key, dsaEncoding: 'ieee-p1363' });
  return `${head}.${body}.${b64url(sig)}`;
}

async function api(method, path, body) {
  const res = await fetch(API + path, {
    method,
    headers: { Authorization: `Bearer ${token()}`, 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  if (!res.ok) {
    const errors = (() => {
      try {
        return JSON.parse(text).errors.map((e) => `${e.title}: ${e.detail}`).join(' | ');
      } catch {
        return text.slice(0, 300);
      }
    })();
    throw new Error(`${method} ${path} → ${res.status} ${errors}`);
  }
  return text ? JSON.parse(text) : null;
}

async function setup(bundleId, dir) {
  const found = await api('GET', `/bundleIds?filter[identifier]=${encodeURIComponent(bundleId)}&filter[platform]=IOS`);
  const bundle = found.data.find((b) => b.attributes.identifier === bundleId);
  if (!bundle) throw new Error(`No App ID ${bundleId} in the team: register it (with Apple Pay) under Identifiers.`);

  // A fresh key and certificate request for this build only (RSA 2048, as Apple requires).
  const keyPath = join(dir, 'key.pem');
  const csrPath = join(dir, 'csr.pem');
  execFileSync('/usr/bin/openssl', ['req', '-new', '-newkey', 'rsa:2048', '-nodes', '-keyout', keyPath, '-out', csrPath, '-subj', '/CN=Vezmo CI'], { stdio: 'ignore' });
  const cert = await api('POST', '/certificates', {
    data: { type: 'certificates', attributes: { certificateType: 'DISTRIBUTION', csrContent: readFileSync(csrPath, 'utf8') } },
  });
  writeFileSync(join(dir, 'cert.cer'), Buffer.from(cert.data.attributes.certificateContent, 'base64'));

  const name = `Vezmo Example CI ${process.env.GITHUB_RUN_ID ?? Date.now()}`;
  const profile = await api('POST', '/profiles', {
    data: {
      type: 'profiles',
      attributes: { name, profileType: 'IOS_APP_STORE' },
      relationships: {
        bundleId: { data: { type: 'bundleIds', id: bundle.id } },
        certificates: { data: [{ type: 'certificates', id: cert.data.id }] },
      },
    },
  });
  writeFileSync(join(dir, 'profile.mobileprovision'), Buffer.from(profile.data.attributes.profileContent, 'base64'));
  writeFileSync(join(dir, 'ids.json'), JSON.stringify({ certificate: cert.data.id, profile: profile.data.id }));
  console.log(`PROFILE_NAME=${name}`);
  console.log(`PROFILE_UUID=${profile.data.attributes.uuid}`);
}

async function cleanup(dir) {
  let ids;
  try {
    ids = JSON.parse(readFileSync(join(dir, 'ids.json'), 'utf8'));
  } catch {
    return;
  }
  for (const [path, label] of [[`/profiles/${ids.profile}`, 'profile'], [`/certificates/${ids.certificate}`, 'certificate']]) {
    try {
      await api('DELETE', path);
      console.log(`Removed the temporary ${label}.`);
    } catch (e) {
      console.log(`Could not remove the temporary ${label}: ${e.message}`);
    }
  }
}

/** Where the app's builds are in TestFlight; a processed build is added to every internal group that lacks it. */
async function testflight(bundleId) {
  const apps = await api('GET', `/apps?filter[bundleId]=${encodeURIComponent(bundleId)}`);
  const app = apps.data[0];
  if (!app) throw new Error(`No app with bundle id ${bundleId} in App Store Connect.`);
  const builds = await api('GET', `/builds?filter[app]=${app.id}&sort=-uploadedDate&limit=3&include=buildBetaDetail`);
  const details = Object.fromEntries((builds.included ?? []).map((d) => [d.id, d.attributes]));
  for (const b of builds.data) {
    const beta = details[b.relationships?.buildBetaDetail?.data?.id] ?? {};
    console.log(
      `Build ${b.attributes.version}: processing ${b.attributes.processingState}, TestFlight ${beta.internalBuildState ?? '?'},` +
        ` encryption answered ${b.attributes.usesNonExemptEncryption ?? 'no'}, expired ${b.attributes.expired}`,
    );
  }
  const latest = builds.data[0];
  if (!latest) return console.log('No builds uploaded yet.');
  if (latest.attributes.usesNonExemptEncryption == null) {
    await api('PATCH', `/builds/${latest.id}`, { data: { type: 'builds', id: latest.id, attributes: { usesNonExemptEncryption: false } } });
    console.log('Answered the encryption question for the latest build (standard encryption only).');
  }
  const groups = await api('GET', `/apps/${app.id}/betaGroups`);
  for (const g of groups.data.filter((x) => x.attributes.isInternalGroup)) {
    const inGroup = await api('GET', `/betaGroups/${g.id}/builds?limit=20`);
    const testers = await api('GET', `/betaGroups/${g.id}/betaTesters?limit=50`);
    const states = testers.data.map((t) => t.attributes.state ?? '?').join(', ') || 'none';
    const has = inGroup.data.some((x) => x.id === latest.id);
    console.log(`Internal group "${g.attributes.name}": testers ${testers.data.length} (${states}); latest build in group: ${has}`);
    if (!has && latest.attributes.processingState === 'VALID') {
      await api('POST', `/betaGroups/${g.id}/relationships/builds`, { data: [{ type: 'builds', id: latest.id }] });
      console.log(`Added build ${latest.attributes.version} to "${g.attributes.name}".`);
    }
  }
}

const [cmd, a, b] = process.argv.slice(2);
try {
  if (cmd === 'setup') await setup(a, b);
  else if (cmd === 'cleanup') await cleanup(a);
  else if (cmd === 'testflight') await testflight(a);
  else throw new Error('usage: asc-signing.mjs setup <bundleId> <dir> | cleanup <dir>');
} catch (e) {
  console.error(e.message);
  process.exit(1);
}
