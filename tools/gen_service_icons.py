"""Generates the bundled icon table of et-otp-v2 (issue #27) from the simple-icons npm package.

Writes src/main/resources/net/disk0401/etotp/services.txt and src/main/resources/META-INF/LICENSE-simple-icons.txt.
Only for maintainers; the build does not run it. To update the icons:

    curl -o si.tgz https://registry.npmjs.org/simple-icons/-/simple-icons-<version>.tgz
    mkdir si && tar xzf si.tgz -C si
    python3 -I tools/gen_service_icons.py si/package .

Edit TABLE below to add services. Icons with a license other than CC0 in the Simple Icons data are refused.
"""
import json
import re
import sys

pkg, repo = sys.argv[1], sys.argv[2]
VERSION = json.load(open(pkg + '/package.json'))['version']

# slug (None: no bundled icon, only the domain), issuer names, domain ('' if self-hosted)
TABLE = [
    ('github', ['GitHub'], 'github.com'),
    ('gitlab', ['GitLab'], 'gitlab.com'),
    ('bitbucket', ['Bitbucket'], 'bitbucket.org'),
    ('atlassian', ['Atlassian'], 'atlassian.com'),
    ('trello', ['Trello'], 'trello.com'),
    ('google', ['Google', 'Google Account'], 'google.com'),
    ('googlecloud', ['Google Cloud', 'Google Cloud Platform'], 'cloud.google.com'),
    ('apple', ['Apple', 'Apple ID', 'Apple Account'], 'apple.com'),
    ('dropbox', ['Dropbox'], 'dropbox.com'),
    ('box', ['Box'], 'box.com'),
    ('facebook', ['Facebook'], 'facebook.com'),
    ('meta', ['Meta'], 'meta.com'),
    ('instagram', ['Instagram'], 'instagram.com'),
    ('x', ['X', 'Twitter'], 'x.com'),
    ('discord', ['Discord'], 'discord.com'),
    ('zoom', ['Zoom'], 'zoom.us'),
    ('cloudflare', ['Cloudflare'], 'cloudflare.com'),
    ('digitalocean', ['DigitalOcean'], 'digitalocean.com'),
    ('npm', ['npm', 'npmjs.com'], 'npmjs.com'),
    ('pypi', ['PyPI'], 'pypi.org'),
    ('rubygems', ['RubyGems', 'RubyGems.org'], 'rubygems.org'),
    ('nuget', ['NuGet', 'NuGet.org'], 'nuget.org'),
    ('docker', ['Docker', 'Docker Hub'], 'docker.com'),
    ('twitch', ['Twitch'], 'twitch.tv'),
    ('reddit', ['Reddit'], 'reddit.com'),
    ('epicgames', ['Epic Games'], 'epicgames.com'),
    ('playstation', ['PlayStation', 'PlayStation Network'], 'playstation.com'),
    ('ubisoft', ['Ubisoft'], 'ubisoft.com'),
    ('ea', ['EA', 'Electronic Arts'], 'ea.com'),
    ('battledotnet', ['Battle.net', 'Blizzard'], 'battle.net'),
    ('riotgames', ['Riot Games'], 'riotgames.com'),
    ('roblox', ['Roblox'], 'roblox.com'),
    ('squareenix', ['Square Enix'], 'square-enix.com'),
    ('coinbase', ['Coinbase'], 'coinbase.com'),
    ('binance', ['Binance'], 'binance.com'),
    ('kucoin', ['KuCoin'], 'kucoin.com'),
    ('okx', ['OKX'], 'okx.com'),
    ('paypal', ['PayPal'], 'paypal.com'),
    ('stripe', ['Stripe'], 'stripe.com'),
    ('wise', ['Wise'], 'wise.com'),
    ('revolut', ['Revolut'], 'revolut.com'),
    ('robinhood', ['Robinhood'], 'robinhood.com'),
    ('proton', ['Proton', 'Proton Mail'], 'proton.me'),
    ('tuta', ['Tuta', 'Tutanota'], 'tuta.com'),
    ('zoho', ['Zoho'], 'zoho.com'),
    ('1password', ['1Password'], '1password.com'),
    ('bitwarden', ['Bitwarden'], 'bitwarden.com'),
    ('lastpass', ['LastPass'], 'lastpass.com'),
    ('dashlane', ['Dashlane'], 'dashlane.com'),
    ('keeper', ['Keeper', 'Keeper Security'], 'keepersecurity.com'),
    ('okta', ['Okta'], 'okta.com'),
    ('auth0', ['Auth0'], 'auth0.com'),
    ('shopify', ['Shopify'], 'shopify.com'),
    ('mailchimp', ['Mailchimp'], 'mailchimp.com'),
    ('hubspot', ['HubSpot'], 'hubspot.com'),
    ('zendesk', ['Zendesk'], 'zendesk.com'),
    ('notion', ['Notion'], 'notion.so'),
    ('figma', ['Figma'], 'figma.com'),
    ('miro', ['Miro'], 'miro.com'),
    ('asana', ['Asana'], 'asana.com'),
    ('clickup', ['ClickUp'], 'clickup.com'),
    ('airtable', ['Airtable'], 'airtable.com'),
    ('evernote', ['Evernote'], 'evernote.com'),
    ('line', ['LINE'], 'line.me'),
    ('rakuten', ['Rakuten', '楽天'], 'rakuten.co.jp'),
    ('dmm', ['DMM', 'DMM.com'], 'dmm.com'),
    ('pixiv', ['pixiv'], 'pixiv.net'),
    ('niconico', ['niconico', 'ニコニコ'], 'nicovideo.jp'),
    ('zaim', ['Zaim'], 'zaim.net'),
    ('namecheap', ['Namecheap'], 'namecheap.com'),
    ('godaddy', ['GoDaddy'], 'godaddy.com'),
    ('ovh', ['OVH', 'OVHcloud'], 'ovhcloud.com'),
    ('hetzner', ['Hetzner'], 'hetzner.com'),
    ('vultr', ['Vultr'], 'vultr.com'),
    ('gandi', ['Gandi'], 'gandi.net'),
    ('porkbun', ['Porkbun'], 'porkbun.com'),
    ('vercel', ['Vercel'], 'vercel.com'),
    ('netlify', ['Netlify'], 'netlify.com'),
    ('render', ['Render'], 'render.com'),
    ('railway', ['Railway'], 'railway.com'),
    ('flydotio', ['Fly.io'], 'fly.io'),
    ('supabase', ['Supabase'], 'supabase.com'),
    ('planetscale', ['PlanetScale'], 'planetscale.com'),
    ('fastly', ['Fastly'], 'fastly.com'),
    ('sentry', ['Sentry'], 'sentry.io'),
    ('datadog', ['Datadog'], 'datadoghq.com'),
    ('newrelic', ['New Relic'], 'newrelic.com'),
    ('grafana', ['Grafana', 'Grafana Labs', 'Grafana Cloud'], 'grafana.com'),
    ('hashicorp', ['HashiCorp'], 'hashicorp.com'),
    ('mongodb', ['MongoDB', 'MongoDB Atlas'], 'mongodb.com'),
    ('redis', ['Redis', 'Redis Cloud'], 'redis.io'),
    ('elastic', ['Elastic', 'Elastic Cloud'], 'elastic.co'),
    ('jetbrains', ['JetBrains'], 'jetbrains.com'),
    ('circleci', ['CircleCI'], 'circleci.com'),
    ('travisci', ['Travis CI'], 'travis-ci.com'),
    ('codeberg', ['Codeberg'], 'codeberg.org'),
    ('sourcehut', ['SourceHut', 'sr.ht'], 'sr.ht'),
    ('launchpad', ['Launchpad'], 'launchpad.net'),
    ('huggingface', ['Hugging Face'], 'huggingface.co'),
    ('replit', ['Replit'], 'replit.com'),
    ('anthropic', ['Anthropic'], 'anthropic.com'),
    ('wordpress', ['WordPress', 'WordPress.com'], 'wordpress.com'),
    ('tumblr', ['Tumblr'], 'tumblr.com'),
    ('pinterest', ['Pinterest'], 'pinterest.com'),
    ('snapchat', ['Snapchat'], 'snapchat.com'),
    ('tiktok', ['TikTok'], 'tiktok.com'),
    ('mozilla', ['Mozilla', 'Firefox', 'Firefox Accounts', 'Mozilla accounts'], 'mozilla.org'),
    ('ebay', ['eBay'], 'ebay.com'),
    ('etsy', ['Etsy'], 'etsy.com'),
    ('alibabacloud', ['Alibaba Cloud', 'Aliyun'], 'alibabacloud.com'),
    ('uber', ['Uber'], 'uber.com'),
    ('patreon', ['Patreon'], 'patreon.com'),
    ('kofi', ['Ko-fi'], 'ko-fi.com'),
    ('gumroad', ['Gumroad'], 'gumroad.com'),
    ('squarespace', ['Squarespace'], 'squarespace.com'),
    ('wix', ['Wix'], 'wix.com'),
    ('ghost', ['Ghost'], 'ghost.org'),
    ('substack', ['Substack'], 'substack.com'),
    ('hackerone', ['HackerOne'], 'hackerone.com'),
    ('bugcrowd', ['Bugcrowd'], 'bugcrowd.com'),
    ('backblaze', ['Backblaze'], 'backblaze.com'),
    ('wasabi', ['Wasabi'], 'wasabi.com'),
    ('synology', ['Synology'], 'synology.com'),
    ('qnap', ['QNAP'], 'qnap.com'),
    ('ubiquiti', ['Ubiquiti', 'UniFi'], 'ui.com'),
    ('nordvpn', ['NordVPN', 'Nord Account'], 'nordvpn.com'),
    ('expressvpn', ['ExpressVPN'], 'expressvpn.com'),
    ('openvpn', ['OpenVPN'], 'openvpn.net'),
    ('fortinet', ['Fortinet', 'FortiCloud'], 'fortinet.com'),
    ('cisco', ['Cisco'], 'cisco.com'),
    ('citrix', ['Citrix'], 'citrix.com'),
    ('vmware', ['VMware'], 'vmware.com'),
    ('sap', ['SAP'], 'sap.com'),
    ('samsung', ['Samsung', 'Samsung Account'], 'samsung.com'),
    ('xiaomi', ['Xiaomi', 'Mi Account'], 'mi.com'),
    ('huawei', ['Huawei', 'HUAWEI ID'], 'huawei.com'),
    ('nextcloud', ['Nextcloud'], ''),
    ('gitea', ['Gitea'], ''),
    ('mastodon', ['Mastodon'], ''),
    # removed from Simple Icons at the request of the brands: only the domain, for fetching the icon
    (None, ['Microsoft', 'Microsoft account'], 'microsoft.com'),
    (None, ['Amazon'], 'amazon.com'),
    (None, ['Amazon Web Services', 'AWS'], 'aws.amazon.com'),
    (None, ['Slack'], 'slack.com'),
    (None, ['LinkedIn'], 'linkedin.com'),
    (None, ['Adobe', 'Adobe ID'], 'adobe.com'),
    (None, ['Yahoo'], 'yahoo.com'),
    (None, ['Yahoo! JAPAN'], 'yahoo.co.jp'),
    (None, ['Salesforce'], 'salesforce.com'),
    (None, ['Heroku'], 'heroku.com'),
    (None, ['Twilio'], 'twilio.com'),
    (None, ['Kraken'], 'kraken.com'),
    (None, ['Nintendo', 'Nintendo Account'], 'nintendo.com'),
    (None, ['Canva'], 'canva.com'),
    (None, ['Linode'], 'linode.com'),
    (None, ['IBM', 'IBMid'], 'ibm.com'),
    (None, ['Coincheck'], 'coincheck.com'),
    (None, ['bitFlyer'], 'bitflyer.com'),
    (None, ['Money Forward', 'マネーフォワード'], 'moneyforward.com'),
    (None, ['freee'], 'freee.co.jp'),
    (None, ['ConoHa'], 'conoha.jp'),
    (None, ['Backlog'], 'backlog.com'),
    (None, ['Chatwork'], 'chatwork.com'),
]

icons = {i['slug']: i for i in json.load(open(pkg + '/data/simple-icons.json'))}
lines = ['# Bundled service icons and the sites of services (et-otp-v2 issue #27). Generated by tools/gen_service_icons.py; UTF-8.',
         '# Icons: Simple Icons %s (https://simpleicons.org), CC0-1.0; see META-INF/LICENSE-simple-icons.txt.' % VERSION,
         '# Each line: issuer names separated by |, TAB, domain of the site (empty if self-hosted), TAB,',
         '# brand color (hex, empty without icon), TAB, SVG path data in a 24 x 24 box (empty without icon).']
notice = []
seen = set()
keys = set()
def key(n):
    import unicodedata
    return ''.join(c for c in unicodedata.normalize('NFKC', n).lower() if c.isalnum())
for slug, names, domain in TABLE:
    for n in names:
        assert '|' not in n and '\t' not in n
        assert key(n) not in keys, n
        keys.add(key(n))
    color = path = ''
    if slug:
        assert slug not in seen, slug
        seen.add(slug)
        info = icons[slug]
        lic = info.get('license')
        assert lic is None or lic.get('type') == 'CC0-1.0', (slug, lic)
        svg = open('%s/icons/%s.svg' % (pkg, slug), encoding='utf-8').read()
        m = re.findall(r'<path d="([^"]+)"', svg)
        assert len(m) == 1 and 'viewBox="0 0 24 24"' in svg, slug
        path = m[0]
        color = info['hex']
        notice.append('%s (%s)\n    source: %s' % (info['title'], slug, info['source']))
    lines.append('\t'.join(['|'.join(names), domain, color, path]))

with open(repo + '/src/main/resources/net/disk0401/etotp/services.txt', 'w', encoding='utf-8', newline='\n') as f:
    f.write('\n'.join(lines) + '\n')

head = '''et-otp-v2 shows icons of the following services, drawn from the SVG files of
Simple Icons %s (https://github.com/simple-icons/simple-icons). Simple Icons is
released under CC0 1.0 Universal (https://creativecommons.org/publicdomain/zero/1.0/);
the icons listed here have no other license in its data.

CC0 covers copyright only. The logos are trademarks of their owners. et-otp-v2 shows
them only to identify the services a user registered; it is not affiliated with,
endorsed or sponsored by any of them.

The icons (title, slug in Simple Icons, source given by Simple Icons):

''' % VERSION
with open(repo + '/src/main/resources/META-INF/LICENSE-simple-icons.txt', 'w', encoding='utf-8', newline='\n') as f:
    f.write(head + '\n'.join(notice) + '\n')
print(len(TABLE), 'entries', len(notice), 'icons')
