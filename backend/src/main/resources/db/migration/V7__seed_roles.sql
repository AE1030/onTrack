-- UserService.register assigns role id 2 to every new account, but no migration ever created the
-- role rows, so a fresh database (production) failed every signup with "Role not found".
-- ON CONFLICT keeps this safe on databases that already have the rows (dev, or a manual fix).
INSERT INTO role (id, role_name) VALUES
    (1, 'ROLE_ADMIN'),
    (2, 'ROLE_USER')
ON CONFLICT (id) DO NOTHING;

-- The ids were given explicitly, so move the identity sequence past them.
SELECT setval('role_id_seq', GREATEST((SELECT MAX(id) FROM role), 1));
