// Run by the bundled Bun inside run.sh's proot environment. No provider/broker keys here.
import net from 'node:net';

const [portText, claim, ...agentArgs] = process.argv.slice(2);
const port = Number(portText);
if (!Number.isInteger(port) || port < 1 || port > 65535 || !/^[A-Za-z0-9_-]{43}$/.test(claim ?? '')) {
    process.stderr.write('Invalid RPC bridge arguments\n');
    process.exit(2);
}
const socket = net.createConnection({ host: '127.0.0.1', port });
let child;
let header = Buffer.alloc(0);
let authenticated = false;
let ending = false;
const timeout = setTimeout(() => stop(1), 30_000);
function stop(code) {
    if (ending) return;
    ending = true;
    clearTimeout(timeout);
    socket.destroy();
    if (child) {
        // A new detached process group also owns omp's subprocesses.
        try { process.kill(-child.pid, 'SIGTERM'); } catch { child.kill('SIGTERM'); }
        setTimeout(() => {
            try { process.kill(-child.pid, 'SIGKILL'); } catch { child.kill('SIGKILL'); }
            process.exit(code);
        }, 1000);
    } else process.exit(code);
}
socket.on('connect', () => {
    socket.setNoDelay(true);
    socket.write(JSON.stringify({ type: 'claim', code: claim }) + '\n');
});
socket.on('data', data => {
    if (authenticated) return;
    header = Buffer.concat([header, data]);
    if (header.length > 256) return stop(1);
    const end = header.indexOf(10);
    if (end < 0) return;
    let frame;
    try { frame = JSON.parse(header.subarray(0, end).toString('utf8')); } catch { return stop(1); }
    if (frame.type !== 'claimed') return stop(1);
    clearTimeout(timeout);
    authenticated = true;
    // node:child_process streams provide backpressure in both directions under Bun.
    import('node:child_process').then(({ spawn }) => {
        if (ending) return;
        child = spawn('omp', ['--mode', 'rpc', '--no-ui', '--approval-mode', 'yolo', ...agentArgs], {
            stdio: ['pipe', 'pipe', 'pipe'], detached: true,
        });
        process.stderr.write(`rpc-bridge: starting omp ${new Date().toISOString()}\n`);
        child.on('error', e => { process.stderr.write(`rpc-bridge: omp failed to start: ${e && e.message}\n`); stop(1); });
        child.stdin.on('error', () => stop(1));
        child.stdout.on('error', () => stop(1));
        // Diagnostics go to the bridge log (stderr), never to JSONL stdout.
        child.stderr.pipe(process.stderr);
        child.stdout.pipe(socket, { end: false });
        const rest = header.subarray(end + 1);
        if (rest.length) child.stdin.write(rest);
        socket.pipe(child.stdin);
        child.on('exit', (code, signal) => { process.stderr.write(`rpc-bridge: omp exited code=${code} signal=${signal}\n`); stop(code === 0 ? 0 : 1); });
    }).catch(() => stop(1));
});
socket.on('error', () => stop(1));
socket.on('close', () => stop(1));
process.on('SIGTERM', () => stop(0));
process.on('SIGINT', () => stop(0));
