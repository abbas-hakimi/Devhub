"""Narrow API cleanup independent of the lifetime of the Chrome process."""
import json
from http.cookiejar import CookieJar
from urllib.request import build_opener, HTTPCookieProcessor, Request
from urllib.error import HTTPError
from conftest import BACKEND, FRONTEND


def cleanup_repository(credentials, name, repository_id):
    client = build_opener(HTTPCookieProcessor(CookieJar()))
    token = None

    def call(method, path, payload=None):
        headers = {'Origin': FRONTEND, 'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        request = Request(BACKEND + '/api' + path, method=method, headers=headers,
                          data=json.dumps(payload).encode() if payload is not None else None)
        try:
            with client.open(request, timeout=15) as response:
                body = response.read()
                return response.status, json.loads(body) if body else None
        except HTTPError as error:
            return error.code, None

    try:
        status, login = call('POST', '/auth/login', {'identifier': credentials[0], 'password': credentials[1]})
        if status != 200:
            raise RuntimeError('Cleanup login failed')
        token = login['accessToken']
        owner_id = login['user']['id']
        status, records = call('GET', '/repositories/mine')
        if status != 200:
            raise RuntimeError('Cleanup repository lookup failed')
        matches = [r for r in records if r['owner']['id'] == owner_id
                   and r['name'] in (name, name + '_edited')
                   and (repository_id is None or str(r['id']) == str(repository_id))]
        for record in matches:
            status, _ = call('DELETE', '/repositories/' + str(record['id']))
            if status not in (204, 404):
                raise RuntimeError(f"Cleanup blocked for disposable repository {record['id']} (HTTP {status})")
    except Exception:
        # Do not include HTTP headers, credentials, or response bodies.
        raise RuntimeError(f'Cleanup could not be confirmed for test repository {name}; inspect and retry only this resource.') from None
    finally:
        if token:
            try:
                call('POST', '/auth/logout')
            except Exception:
                pass
