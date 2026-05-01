export default {
  async fetch(request, env) {

    if (request.method === 'OPTIONS') return cors(new Response(null, { status: 204 }), env);
    const url = new URL(request.url);
    try {
      let res;
      if      (url.pathname === '/build'    && request.method === 'POST') res = await handleBuild(request, env);
      else if (url.pathname === '/status'   && request.method === 'GET')  res = await handleStatus(request, env);
      else if (url.pathname === '/logs'     && request.method === 'GET')  res = await handleLogs(request, env);
      else if (url.pathname === '/download' && request.method === 'GET')  res = await handleDownload(request, env);
      else if (url.pathname === '/cancel'   && request.method === 'POST') res = await handleCancel(request, env);
      else return env.ASSETS.fetch(request);
      return cors(res, env);
    } catch (e) {
      return cors(json({ error: e.message }, 500), env);
    }
  }
};

async function handleBuild(request, env) {
  const { app_url, app_name, package_name, version_name, icon_url, no_screenshot } = await request.json();
  if (!app_url || !app_name || !package_name || !version_name)
    return json({ error: 'Missing required fields' }, 400);
  const pkgRe = /^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*){1,}$/;
  if (!pkgRe.test(package_name))
    return json({ error: 'Invalid package name' }, 400);
  // 校验包名各段不能是 Java 关键字
  const JAVA_KEYWORDS = new Set(["abstract","assert","boolean","break","byte","case","catch","char","class","const","continue","default","do","double","else","enum","extends","final","finally","float","for","goto","if","implements","import","instanceof","int","interface","long","native","new","package","private","protected","public","return","short","static","strictfp","super","switch","synchronized","this","throw","throws","transient","try","void","volatile","while"]);
  const invalidSeg = package_name.split('.').find(seg => JAVA_KEYWORDS.has(seg));
  if (invalidSeg)
    return json({ error: `Package segment '${invalidSeg}' is a Java keyword and cannot be used in a package name` }, 400);
  // version_name 支持任意字符（1.0、2.0-beta、v3.1.0-rc1 等），仅限制长度
  if (!version_name || version_name.length > 32)
    return json({ error: 'version_name must be 1-32 characters' }, 400);

  const r = await gh(env,
    `/repos/${env.GITHUB_OWNER}/${env.GITHUB_REPO}/actions/workflows/build.yml/dispatches`,
    { method: 'POST', body: JSON.stringify({
        ref: 'main',
        inputs: { app_url, app_name, package_name, version_name, icon_url: icon_url || 'https://apk.091224.xyz/logo.jpg', no_screenshot: no_screenshot||'false' }
    })}
  );
  if (r.status !== 204) return json({ error: 'Trigger failed', detail: await r.text() }, 500);

  // 记录触发时间，轮询直到出现比此时间更新的 run，避免拿到上一次的 run_id
  const triggeredAt = new Date(Date.now() - 8000); // 8秒容差：往前留余量，避免漏匹配新run
  let runId = null;
  for (let i = 0; i < 30; i++) {  // 最多等60秒
    await sleep(2000);
    const runs = await (await gh(env,
      `/repos/${env.GITHUB_OWNER}/${env.GITHUB_REPO}/actions/workflows/build.yml/runs?per_page=10`
    )).json();
    const fresh = runs.workflow_runs?.find(r => new Date(r.created_at) >= triggeredAt);
    if (fresh) { runId = fresh.id; break; }
  }
  if (!runId) return json({ error: 'Could not get run_id after 60s' }, 500);
  return json({ run_id: runId, status: 'queued' });
}

async function handleStatus(request, env) {
  const runId = new URL(request.url).searchParams.get('run_id');
  if (!runId) return json({ error: 'Missing run_id' }, 400);
  const data = await (await gh(env,
    `/repos/${env.GITHUB_OWNER}/${env.GITHUB_REPO}/actions/runs/${runId}`
  )).json();
  const result = { run_id: runId, status: data.status, conclusion: data.conclusion, job_id: null, step_index: 0, step_total: 0 };

  // 解析 job steps 获取精确进度
  const jobsRes = await gh(env,
    `/repos/${env.GITHUB_OWNER}/${env.GITHUB_REPO}/actions/runs/${runId}/jobs`
  );
  const jobs = await jobsRes.json();
  const job = jobs.jobs?.[0];
  if (job) {
    result.job_id = job.id;
    const steps = job.steps || [];
    const userSteps = steps.filter(s => !['Set up job','Post','Complete job','Cache'].some(k => s.name.includes(k)));
    const stepMap = { 'Inject':15,'Process':30,'Build':70,'Sign':90,'Upload':100 };
    let progress = 5, currentStep = '', stepIndex = 0;
    for (const step of userSteps) {
      if (step.status === 'completed' && step.conclusion === 'success') {
        stepIndex++;
        for (const [name, pct] of Object.entries(stepMap)) {
          if (step.name.includes(name)) progress = Math.max(progress, pct);
        }
      }
      if (step.status === 'in_progress') {
        currentStep = step.name;
        const base = Object.entries(stepMap).find(([n]) => step.name.includes(n));
        if (base) progress = Math.max(progress, base[1] - 10);
      }
    }
    result.progress    = progress;
    result.current_step = currentStep;
    result.step_index  = stepIndex;
    result.step_total  = userSteps.length || 5;
  }

  if (data.status === 'completed' && data.conclusion === 'success') {
    result.progress = 100;
    const arts = await (await gh(env,
      `/repos/${env.GITHUB_OWNER}/${env.GITHUB_REPO}/actions/runs/${runId}/artifacts`
    )).json();
    // 返回全部 artifacts，让前端展示多个 APK 下载选项
    if (arts.artifacts?.length) {
      result.artifacts = arts.artifacts.map(a => ({
        id: a.id,
        name: a.name,
      }));
      // 兼容旧字段
      result.artifact_id   = arts.artifacts[0].id;
      result.artifact_name = arts.artifacts[0].name;
    }
  }

  // 构建失败时找出失败步骤，并拉取该步骤的日志片段
  if (data.status === 'completed' && data.conclusion === 'failure') {
    const steps = jobs.jobs?.[0]?.steps || [];
    const failedStep = steps.find(s => s.conclusion === 'failure');
    if (failedStep) {
      result.failed_step = failedStep.name;
      // 拉取日志，截取最后 30 行作为错误摘要
      try {
        const logRes = await gh(env,
          `/repos/${env.GITHUB_OWNER}/${env.GITHUB_REPO}/actions/jobs/${jobs.jobs[0].id}/logs`
        );
        if (logRes.ok) {
          const logText = await logRes.text();
          const lines = logText.split('\n').filter(l => l.trim());
          // 找失败步骤附近的错误行（含 Error/error/FAILED/exception）
          const errLines = lines.filter(l =>
            /error|failed|exception|cannot|unable|no such/i.test(l) &&
            !/^##\[group\]|^##\[endgroup\]/i.test(l)
          );
          result.failed_log = errLines.slice(-8).map(l =>
            l.replace(/^\d{4}-\d{2}-\d{2}T[\d:.]+Z /, '').trim()
          ).join('\n');
        }
      } catch (_) {}
    }
  }

  return json(result);
}

async function handleLogs(request, env) {
  const p = new URL(request.url).searchParams;
  const runId = p.get('run_id'), jobId = p.get('job_id');
  if (!runId) return json({ lines: [] });
  let jid = jobId;
  if (!jid) {
    const jr = await gh(env, `/repos/${env.GITHUB_OWNER}/${env.GITHUB_REPO}/actions/runs/${runId}/jobs`);
    const jd = await jr.json();
    jid = jd.jobs?.[0]?.id;
  }
  if (!jid) return json({ lines: [] });
  try {
    const lr = await gh(env, `/repos/${env.GITHUB_OWNER}/${env.GITHUB_REPO}/actions/jobs/${jid}/logs`);
    if (!lr.ok) return json({ lines: [] });
    const raw = await lr.text();
    const lines = raw.split('\n')
      .map(l => l.replace(/^\d{4}-\d{2}-\d{2}T[\d:.]+Z /, '').replace(/\x1b\[[\d;]*m/g,'').trim())
      .filter(l => l &&
        !/^##\[group|^##\[endgroup/i.test(l) &&
        !/California|MIPS|Evaluation|Recipient|UL or FCC|Pre-Release|GOOGLE_|LIMITATION|LICENSE|jurisdic/i.test(l) &&
        !/^shell:|^env:|^with:|JAVA_HOME|ANDROID_HOME|GRADLE_USER/i.test(l)
      );
    return json({ lines: lines.slice(-150) });
  } catch(_) { return json({ lines: [] }); }
}

async function handleDownload(request, env) {
  const params     = new URL(request.url).searchParams;
  const runId      = params.get('run_id');
  const artifactId = params.get('artifact_id');
  if (!runId) return json({ error: 'Missing run_id' }, 400);

  let resolvedId = artifactId;
  let artifactName = 'apk';

  if (!resolvedId) {
    // 没有指定 artifact_id，查列表取第一个
    const arts = await (await gh(env,
      `/repos/${env.GITHUB_OWNER}/${env.GITHUB_REPO}/actions/runs/${runId}/artifacts`
    )).json();
    const a = arts.artifacts?.[0];
    if (!a) return json({ error: 'Artifact not found' }, 404);
    resolvedId = a.id;
    artifactName = a.name;
  }

  // GitHub artifact 下载：先拿重定向 URL，再不带 Auth 头去 S3 下载
  const dlRedirect = await gh(env,
    `/repos/${env.GITHUB_OWNER}/${env.GITHUB_REPO}/actions/artifacts/${resolvedId}/zip`,
    { redirect: 'manual' }
  );
  // 302 重定向到 S3，不能带 Authorization 头
  const s3Url = dlRedirect.headers.get('location');
  if (!s3Url) return json({ error: 'Download redirect failed', status: dlRedirect.status }, 502);

  const dl = await fetch(s3Url);
  if (!dl.ok) return json({ error: 'Download failed from S3', status: dl.status }, 502);
  const zipBuf = await dl.arrayBuffer();

  // 尝试解压，直接返回 .apk
  try {
    const apk = await extractApkFromZip(zipBuf);
    if (apk) {
      const apkName = artifactName.replace(/\.zip$/, '') + '.apk';
      return new Response(apk, {
        headers: {
          'Content-Type': 'application/vnd.android.package-archive',
          'Content-Disposition': `attachment; filename="${apkName}"`,
          'Content-Length': apk.byteLength.toString(),
          'Cache-Control': 'no-store',
        }
      });
    }
  } catch (_) {}

  // 解压失败，降级返回原始 ZIP
  return new Response(zipBuf, {
    headers: {
      'Content-Type': 'application/zip',
      'Content-Disposition': `attachment; filename="${artifactName}.zip"`,
      'Cache-Control': 'no-store',
    }
  });
}

// 解析 ZIP Local File Headers，提取第一个 .apk 文件字节（支持 stored + deflate）
async function extractApkFromZip(buf) {
  const view  = new DataView(buf);
  const bytes = new Uint8Array(buf);
  let offset  = 0;
  while (offset + 30 < bytes.length) {
    if (view.getUint32(offset, true) !== 0x04034b50) break;
    const compression = view.getUint16(offset + 8,  true);
    const compSize    = view.getUint32(offset + 18, true);
    const uncompSize  = view.getUint32(offset + 22, true);
    const nameLen     = view.getUint16(offset + 26, true);
    const extraLen    = view.getUint16(offset + 28, true);
    const name        = new TextDecoder().decode(bytes.slice(offset + 30, offset + 30 + nameLen));
    const dataOffset  = offset + 30 + nameLen + extraLen;
    if (name.endsWith('.apk')) {
      const slice = buf.slice(dataOffset, dataOffset + compSize);
      if (compression === 0) return slice;           // stored
      if (compression === 8) {                       // deflate
        const ds = new DecompressionStream('deflate-raw');
        const writer = ds.writable.getWriter();
        writer.write(new Uint8Array(slice));
        writer.close();
        return new Response(ds.readable).arrayBuffer();
      }
    }
    // fix(bug#5): 跳过可能存在的 data descriptor (0x08074b50, 最多16字节)
    let nextOffset = dataOffset + compSize;
    if (view.getUint32(nextOffset, true) === 0x08074b50) nextOffset += 16;
    offset = nextOffset;
  }
  return null;
}

function gh(env, path, opts = {}) {
  return fetch(`https://api.github.com${path}`, {
    ...opts,
    headers: {
      Authorization: `Bearer ${env.GITHUB_TOKEN}`,
      Accept: 'application/vnd.github+json',
      'X-GitHub-Api-Version': '2022-11-28',
      'User-Agent': 'APK-Builder-CF-Worker/1.0',
      'Content-Type': 'application/json',
      ...(opts.headers || {}),
    }
  });
}

function json(d, s = 200) { return new Response(JSON.stringify(d), { status: s, headers: { 'Content-Type': 'application/json' } }); }
function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }
async function handleCancel(request, env) {
  const runId = new URL(request.url).searchParams.get('run_id');
  if (!runId) return json({ error: 'Missing run_id' }, 400);
  const r = await gh(env,
    `/repos/${env.GITHUB_OWNER}/${env.GITHUB_REPO}/actions/runs/${runId}/cancel`,
    { method: 'POST' }
  );
  // 202 = cancel accepted, 409 = already completed
  if (r.status === 202 || r.status === 409) return json({ ok: true });
  return json({ error: 'Cancel failed', status: r.status }, 500);
}

function cors(res, env) {
  const h = new Headers(res.headers);
  h.set('Access-Control-Allow-Origin',  '*');
  h.set('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  h.set('Access-Control-Allow-Headers', 'Content-Type');
  return new Response(res.body, { status: res.status, headers: h });
}
// force-redeploy: 1777597096
