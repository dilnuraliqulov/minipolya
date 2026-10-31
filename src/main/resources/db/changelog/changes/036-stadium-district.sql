--liquibase formatted sql

--changeset minipolya:036-stadiums-district
--comment: The Tashkent district a stadium is in, picked by its owner, so the admin panel can filter and show it. NULL for stadiums added before it existed. Exactly one of the twelve district names; "Mirzo Ulugʻbek" is written with U+02BB (modifier letter turned comma), as a Unicode escape so the file's encoding cannot change it.
ALTER TABLE stadiums ADD COLUMN district VARCHAR(30);
ALTER TABLE stadiums ADD CONSTRAINT chk_stadiums_district CHECK (district IS NULL OR district IN (
    'Bektemir', 'Chilonzor', 'Mirobod', U&'Mirzo Ulug\02BBbek', 'Olmazor', 'Sergeli',
    'Shayxontohur', 'Uchtepa', 'Yakkasaroy', 'Yangihayot', 'Yashnobod', 'Yunusobod'));
CREATE INDEX idx_stadiums_district ON stadiums (district);
