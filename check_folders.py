import urllib.request, json
r = urllib.request.urlopen('http://localhost:8000/api/folders')
d = json.loads(r.read())
for f in d:
    print(f"{f['id']:3d} | {f['name']:30s} | {f['board_count']} boards")
