-- First-party ("from Crixaa") marketplace listings.
--
-- These are published from a Crixaa-owned org like any other listing, so they
-- need no special ownership model — the flag exists so the console can badge
-- them, sort them ahead of third-party listings, and so the plan gate can let a
-- FREE-plan org browse and install them while the rest of the marketplace stays
-- a Starter+ feature.
--
-- Deliberately NOT a "price" column: nothing in the marketplace has ever had a
-- price, so adding one here would imply a payment path that does not exist.
ALTER TABLE marketplace_listings
    ADD COLUMN official BOOLEAN NOT NULL DEFAULT FALSE;

-- The browse query filters on (published, official) and orders official first,
-- so this covers the common path without scanning third-party rows.
CREATE INDEX IF NOT EXISTS idx_marketplace_listings_official
    ON marketplace_listings (official, published);
