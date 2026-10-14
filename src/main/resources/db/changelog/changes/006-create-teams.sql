--liquibase formatted sql

--changeset minipolya:006-create-teams
--comment: Create teams table — a named group of players with one leader
CREATE TABLE teams (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    leader_id  UUID NOT NULL REFERENCES users (id),
    name       VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_teams_leader_id ON teams (leader_id);

--changeset minipolya:006-create-team-members
--comment: Membership rows linking users to teams (role: leader, member)
CREATE TABLE team_members (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    team_id   UUID NOT NULL REFERENCES teams (id) ON DELETE CASCADE,
    user_id   UUID NOT NULL REFERENCES users (id),
    role      VARCHAR(20) NOT NULL DEFAULT 'member' CHECK (role IN ('leader', 'member')),
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_team_member UNIQUE (team_id, user_id)
);

CREATE INDEX idx_team_members_user_id ON team_members (user_id);
