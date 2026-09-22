"""Generate fork-only contributor blocks; requires git, gh and a full checkout."""
import argparse
import base64
import html
import json
from pathlib import Path
import re
import subprocess
from urllib.request import Request, urlopen

BASE = "82338d7d64725d941f2edb8bc5e4988155869a48"
START = "<!-- koharia-contributors:start -->"
END = "<!-- koharia-contributors:end -->"
ROOT = Path(__file__).resolve().parents[2]


def run(*args):
    return subprocess.check_output(args, cwd=ROOT, encoding="utf-8").strip()


def render(users, names):
    lines = ['<p>']
    for login in sorted(users, key=str.casefold):
        safe = html.escape(login, quote=True)
        lines.append(
            f'  <a href="https://github.com/{safe}"><img '
            f'src="./.github/assets/contributors/{safe}.svg" width="64" height="64" '
            f'alt="{safe}" title="{safe}" /></a>'
        )
    lines.append('</p>')
    if names:
        lines.append('<p>' + ' · '.join(html.escape(n) for n in sorted(names)) + '</p>')
    return '\n'.join(lines)


def avatar_svg(login):
    request = Request(f'https://github.com/{login}.png?size=128', headers={'User-Agent': 'Koharia-contributors'})
    with urlopen(request, timeout=30) as response:
        image = response.read(2_000_001)
        media_type = response.headers.get_content_type()
    if len(image) > 2_000_000 or media_type not in ('image/png', 'image/jpeg', 'image/webp'):
        raise ValueError('Unexpected avatar image')
    encoded = base64.b64encode(image).decode('ascii')
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" '
        'width="128" height="128" viewBox="0 0 128 128">'
        '<defs><clipPath id="circle"><circle cx="64" cy="64" r="64"/></clipPath></defs>'
        f'<image width="128" height="128" preserveAspectRatio="xMidYMid slice" '
        f'clip-path="url(#circle)" xlink:href="data:{media_type};base64,{encoded}"/></svg>\n'
    )


def replace_block(text, content):
    if text.count(START) != 1 or text.count(END) != 1:
        raise ValueError("Expected exactly one contributor block")
    before, rest = text.split(START)
    _, after = rest.split(END)
    return before + START + '\n' + content + '\n' + END + after


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', default='Mister-album/Koharia')
    parser.add_argument('--upstream-ref', required=True)
    args = parser.parse_args()
    run('git', 'merge-base', '--is-ancestor', BASE, 'HEAD')
    run('git', 'rev-parse', '--verify', args.upstream_ref)
    # Exclude inherited and subsequently merged upstream commits, without date assumptions.
    history = run('git', 'log', '--no-merges', '--format=%H%x09%an%x09%ae',
                  'HEAD', '--not', BASE, args.upstream_ref)
    authors = {}
    for line in history.splitlines():
        sha, name, email = line.split('\t', 2)
        if '[bot]' in name.lower() or '[bot]' in email.lower():
            continue
        authors.setdefault(email.lower(), (sha, name))
    users, names = set(), set()
    for sha, name in authors.values():
        commit = json.loads(run('gh', 'api', f'repos/{args.repo}/commits/{sha}'))
        author = commit.get('author')
        if author is None:
            names.add(name)  # Keep unlinked Git authors visible without publishing email addresses.
        elif author.get('type') != 'Bot' and '[bot]' not in author['login'].lower():
            login = author['login']
            if not re.fullmatch(r'[A-Za-z0-9-]+', login):
                raise ValueError('Unexpected GitHub login')
            users.add(login)
    if not users and not names:
        raise ValueError('Refusing to replace contributors with an empty list')
    content = render(users, names)
    updates = {}
    for filename in ('README.md', 'README_EN.md'):
        path = ROOT / filename
        updates[path] = replace_block(path.read_text(encoding='utf-8'), content)
    avatar_dir = ROOT / '.github/assets/contributors'
    # Reuse checked-in images to avoid downloading and rewriting unchanged avatars on every push.
    for login in sorted(users):
        path = avatar_dir / f'{login}.svg'
        if not path.exists():
            updates[path] = avatar_svg(login)
    avatar_dir.mkdir(parents=True, exist_ok=True)
    for path, text in updates.items():
        path.write_text(text, encoding='utf-8', newline='\n')
    print(f'Generated {len(users)} GitHub contributors and {len(names)} unlinked authors.')


if __name__ == '__main__':
    main()
