-- V3: every channel gets an `owner` membership for the user it belongs to.
--
-- Registration now writes this row, so permission checks have one code path
-- ("is there a membership that grants the key?") with no special case for
-- "the channel's own user". Channels created before login existed (the dev
-- seed) have no such row, so without this backfill their owner couldn't
-- upload to their own channel.
--
-- ON CONFLICT DO NOTHING: safe to re-run, and leaves alone any channel that
-- already has a membership row for its user (possibly with another role).
INSERT INTO channel_memberships (channel_id, user_id, role_id)
SELECT c.channel_id, c.user_id, r.role_id
FROM channels c
CROSS JOIN roles r
WHERE r.role_name = 'owner'
ON CONFLICT DO NOTHING;
