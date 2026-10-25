#!/usr/bin/env python3
"""Compare refresh write amplification using production SQL and synthetic episodes.

Host SQLite measurements describe this machine, not Android device latency.
No network, persistent app database or publisher data is used.
"""
import argparse
from pathlib import Path
import sqlite3
import statistics
import sys
import tempfile
import time

sys.path.insert(0, str(Path(__file__).parent / 'tests'))
from test_catalog_sql import production_sql, subscription


def measure(sql, count, previous):
    with tempfile.TemporaryDirectory(prefix='plainpod-refresh-benchmark-') as directory:
        db = sqlite3.connect(str(Path(directory) / 'catalog.db'))
        try:
            db.execute('PRAGMA journal_mode=WAL')
            db.execute('PRAGMA foreign_keys=ON')
            for key, statement in sql.items():
                if key.startswith('create_'):
                    db.execute(statement)
            subscription(db)
            inserts, updates = [], []
            for i in range(count):
                identity = 'episode-' + format(i, '064x')
                audio = f'https://media.example/{i}.mp3'
                inserts.append((identity, 1, audio, 'audio/mpeg', -1, f'Episode {i}', 'Author', '',
                                '', '', 0, 120000, '', '', i, 1, 1, 0, 1, 1))
                updates.append((audio, 'audio/mpeg', -1, f'Episode {i}', 'Author', '', '', '',
                                0, 120000, '', '', i, 2, 2, identity, 1))
            db.executemany(sql['INSERT_EPISODE'], inserts)
            db.commit()
            changes = db.total_changes
            started = time.perf_counter()
            with db:
                if previous:
                    # The verified pre-consolidation algorithm marks every episode stale first.
                    db.execute('UPDATE episodes SET is_stale=1 WHERE subscription_id=?', (1,))
                db.executemany(sql['UPDATE_EPISODE'], updates)
                if not previous:
                    db.execute(sql['MARK_MISSING_EPISODES_STALE'], (1, 2))
            elapsed = (time.perf_counter() - started) * 1000
            assert db.execute('SELECT count(*) FROM episodes WHERE is_stale=0').fetchone()[0] == count
            return elapsed, db.total_changes - changes
        finally:
            db.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--episodes', type=int, default=10000)
    parser.add_argument('--runs', type=int, default=5)
    args = parser.parse_args()
    if not 1 <= args.episodes <= 10000 or not 1 <= args.runs <= 20:
        parser.error('episodes must be 1..10000 and runs 1..20')
    statements = production_sql()
    for previous in (True, False):
        samples = [measure(statements, args.episodes, previous) for _ in range(args.runs)]
        label = 'previous' if previous else 'current'
        print(f'{label}: {args.episodes} episodes, {samples[0][1]} episode updates, '
              f'median {statistics.median(s[0] for s in samples):.2f} ms ({args.runs} runs)')
