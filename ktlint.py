#!/usr/bin/env python3
"""Kotlin LSP client for code diagnostics. Collects all LSP messages."""
import subprocess, json, sys, os, signal, time
from pathlib import Path

def run_diagnostics(project_dir: str, file_path: str, timeout: int = 60):
    proc = subprocess.Popen(
        ['/usr/local/bin/kotlin-language-server'],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        cwd=project_dir, preexec_fn=lambda: signal.signal(signal.SIGPIPE, signal.SIG_DFL)
    )

    def send(msg):
        data = json.dumps(msg).encode('utf-8')
        proc.stdin.write(f'Content-Length: {len(data)}\r\n\r\n'.encode('utf-8'))
        proc.stdin.write(data)
        proc.stdin.flush()

    def recv(timeout_s=5):
        content_length = 0
        end = time.time() + timeout_s
        while time.time() < end:
            if proc.poll() is not None:
                return None
            ready = proc.stdout.readable()
            if not ready:
                time.sleep(0.05)
                continue
            line = proc.stdout.readline()
            if not line:
                time.sleep(0.05)
                continue
            line = line.decode('utf-8', errors='replace').strip()
            if line.startswith('Content-Length:'):
                content_length = int(line.split(':')[1].strip())
            elif line == '' and content_length > 0:
                body = proc.stdout.read(content_length).decode('utf-8', errors='replace')
                return json.loads(body)
        return 'TIMEOUT'

    all_msgs = []
    
    send({
        'jsonrpc': '2.0', 'id': 1, 'method': 'initialize',
        'params': {
            'processId': os.getpid(),
            'capabilities': {
                'textDocument': {'diagnostics': {'dynamicRegistration': False}},
                'workspace': {'didChangeWatchedFiles': {'dynamicRegistration': False}}
            },
            'rootUri': f'file://{project_dir}',
            'workspaceFolders': [{'uri': f'file://{project_dir}', 'name': Path(project_dir).name}]
        }
    })

    # Read all messages until we get initialize result
    end = time.time() + timeout
    init_ok = False
    while time.time() < end and not init_ok:
        msg = recv(5)
        if msg is None:
            break
        if msg == 'TIMEOUT':
            continue
        all_msgs.append(('init', msg))
        if msg.get('id') == 1 and 'result' in msg:
            init_ok = True

    if not init_ok:
        return all_msgs, f'INIT_TIMEOUT after {timeout}s'

    send({'jsonrpc': '2.0', 'method': 'initialized', 'params': {}})

    # Open file
    file_uri = f'file://{file_path}'
    with open(file_path) as f:
        content = f.read()
    
    send({
        'jsonrpc': '2.0', 'method': 'textDocument/didOpen',
        'params': {
            'textDocument': {
                'uri': file_uri, 'languageId': 'kotlin',
                'version': 1, 'text': content
            }
        }
    })

    # Collect messages for remaining time
    end = time.time() + 30
    while time.time() < end:
        msg = recv(3)
        if msg is None or msg == 'TIMEOUT':
            continue
        all_msgs.append(('open', msg))

    # Shutdown
    send({'jsonrpc': '2.0', 'id': 2, 'method': 'shutdown', 'params': {}})
    
    try:
        proc.terminate()
        proc.wait(timeout=3)
    except:
        proc.kill()
    
    return all_msgs, 'OK'

if __name__ == '__main__':
    file_path = os.path.abspath(sys.argv[1])
    project_dir = os.path.abspath(sys.argv[2]) if len(sys.argv) > 2 else os.getcwd()
    print(f'Project: {project_dir}')
    print(f'File:    {file_path}\n')

    msgs, status = run_diagnostics(project_dir, file_path)
    
    print(f'Status: {status}')
    print(f'Messages received: {len(msgs)}\n')
    
    diag_count = 0
    for phase, msg in msgs:
        method = msg.get('method', '?')
        if 'id' in msg:
            method = f'response(id={msg["id"]})'
        if 'error' in msg:
            print(f'  [{phase}] {method} ERROR: {msg["error"]}')
        elif method == 'window/logMessage':
            print(f'  [{phase}] log: {msg.get("params",{}).get("message","")}')
        elif method == 'window/showMessage':
            print(f'  [{phase}] show: {msg.get("params",{}).get("message","")}')
        elif method == 'textDocument/publishDiagnostics':
            diags = msg.get('params', {}).get('diagnostics', [])
            diag_count += len(diags)
            uri = msg.get('params', {}).get('uri', '')
            print(f'  [{phase}] publishDiagnostics ({len(diags)} items): {os.path.basename(uri)}')
            for d in diags:
                r = d.get('range', {})
                s = r.get('start', {})
                sev = {1:'E',2:'W',3:'I',4:'H'}.get(d.get('severity'),'?')
                print(f'     {sev} {s.get("line",0)+1}:{s.get("character",0)+1} {d.get("message","")}')
        elif method.startswith('response'):
            if 'result' in msg:
                caps = msg.get('result', {}).get('capabilities', {})
                print(f'  [{phase}] {method} capabilities: {json.dumps(caps, indent=2)[:400]}')
            else:
                print(f'  [{phase}] {method}')
        else:
            print(f'  [{phase}] {method}: {json.dumps(msg, indent=2)[:200]}')
    
    print(f'\nTotal diagnostics: {diag_count}')
