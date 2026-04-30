/**
 * Cloudflare Pages _worker.js — APK Builder API
 * 置于 Frontend/ 目录（build output dir）
 * 环境变量在 Pages > Settings > Environment variables 配置:
 *   GITHUB_TOKEN / GITHUB_OWNER / GITHUB_REPO
 */

const GH = 'https://api.github.com';

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    // API 路由
    if (request.method === 'OPTIONS') {
      return cors(new Response(null, { status: 204 }));
    }
    if (url.pathname === '/build' && request.method === 'POST') {
      return cors(await handleBuild(request, env));
    }
    if (url.pathname === '/status' && request.method === 'GET') {
      return cors(await handleStatus(request, env));
    }
    if (url.pathname === '/download' && request.method === 'GET') {
      return cors(await handleDownload(request, env));
    }

    // 静态资源 fallback → 由 Pages 服务 index.html 等
    return env.ASSETS.fetch(request);
  }
};

async function handleBuild(request, env) {
  const { app_url, app_name, package_name, version_name, icon_url } = await request.json();
  if (!app_url || !app_name || !package_name || !version_name || !icon_url)
    return json({ error: 'Missing required fields' }, 400);
  const pkgRe = /^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*){2,}$/;
  if (!pkgRe.test(package_name))
    return json({ error: 'Invalid package name' }, 400);

  const r = await gh(env,
    `/repos/${env.GITHUB_OWNER}/${env.GITHUB_REPO}/actions/workflows/build.yml/dispatches`,
    { method: 'POST', body: JSON.stringify({
        ref: 'main',
        inputs: { app_url, app_name, package_name, version_name, icon_url }
    })}
  );
  if (r.status !== 204) return json({ error: 'Trigger failed', detail: await r.text() }, 500);

  // 记录触发时间，轮询直到出现比此时间更新的 run，避免拿到上一次的 run_id
  const triggeredAt = new Date(Date.now() - 3000); // 3秒容差，避免时间精度问题
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
  const result = { run_id: runId, status: data.status, conclusion: data.conclusion };

  // 解析 job steps 获取精确进度
  const jobsRes = await gh(env,
    `/repos/${env.GITHUB_OWNER}/${env.GITHUB_REPO}/actions/runs/${runId}/jobs`
  );
  const jobs = await jobsRes.json();
  const job = jobs.jobs?.[0];
  if (job) {
    const steps = job.steps || [];
    const stepMap = {
      'Inject parameters': 15,
      'Process icon':      30,
      'Build APK':         70,
      'Sign APK':          90,
      'Upload APK':        100,
    };
    let progress = 5;
    let currentStep = '';
    for (const step of steps) {
      if (step.status === 'completed' && step.conclusion === 'success') {
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
    result.progress = progress;
    result.current_step = currentStep;
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
    offset = dataOffset + compSize;
  }
  return null;
}

const gh = (env, path, opts = {}) =>
  fetch(`${GH}${path}`, {
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

const json  = (d, s = 200) => new Response(JSON.stringify(d), {
  status: s, headers: { 'Content-Type': 'application/json' }
});
const sleep = ms => new Promise(r => setTimeout(r, ms));
const cors  = (res, env) => {
  const h = new Headers(res.headers);
  h.set('Access-Control-Allow-Origin',  '*');
  h.set('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  h.set('Access-Control-Allow-Headers', 'Content-Type');
  return new Response(res.body, { status: res.status, headers: h });
};