CREATE TABLE fruit (
  id int default 0,
  name varchar(32) primary key,
  appearance varchar(32),
  cost int,
  grade real);

INSERT INTO fruit (id, name, appearance, cost, grade)
VALUES
  (1, 'Apple', 'red', 59, 87),
  (2, 'Banana', 'yellow', 29, 92.2),
  (3, 'Peach', 'fuzzy', 139, 90.0),
  (4, 'Orange', 'juicy', 89, 88.6);
