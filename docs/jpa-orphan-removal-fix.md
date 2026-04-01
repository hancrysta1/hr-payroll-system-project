# JPA orphanRemoval 의도치 않은 삭제 해결

## 문제

기존 회원(placeholder User)에 연결된 UserBranch를 실제 가입한 유저로 재할당한 뒤, placeholder User를 삭제하는 로직이 있었다. 정상적이라면 UserBranch는 새 유저 쪽에 남아 있어야 하는데, 삭제 직후 branchIds가 빈 배열로 돌아오는 현상이 발생했다.

사업장에 소속된 크루(직원) 정보가 통째로 사라지는 상황이라, 운영에 바로 영향을 주는 이슈였다.

## 분석

User 엔티티의 userBranches 컬렉션에 `orphanRemoval = true`가 설정되어 있었다.

```java
@OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
private List<UserBranch> userBranches;
```

실행 흐름은 다음과 같다.

1. `ub.setUser(alreadyRegisteredUser)` — UserBranch의 소유자를 새 유저로 변경
2. `userRepository.delete(existingUser)` — placeholder User 삭제

2번에서 JPA 영속성 컨텍스트가 placeholder User의 userBranches 컬렉션을 확인한다. 1번에서 setUser로 소유자를 바꿨지만, 영속성 컨텍스트 기준으로는 기존 컬렉션에서 빠진 엔티티로 인식된다. orphanRemoval = true이므로 "부모를 잃은 자식"으로 판단해 UserBranch까지 DELETE 쿼리를 날린다.

결국 옮겨놓은 UserBranch가 전부 삭제되어 branchIds가 빈 배열이 되었다.

## 해결

placeholder User 삭제 시 JPA 영속성 컨텍스트를 거치지 않도록 JPQL 벌크 DELETE로 전환했다.

```java
@Modifying
@Query("DELETE FROM User u WHERE u.id = :id")
void deleteByIdOnly(@Param("id") Long id);
```

JPQL 벌크 연산은 영속성 컨텍스트를 우회해 DB에 직접 DELETE를 실행한다. cascade와 orphanRemoval 모두 JPA 엔티티 라이프사이클 이벤트이므로, 벌크 DELETE에서는 작동하지 않는다. User 테이블의 해당 row만 깔끔하게 삭제되고, 재할당된 UserBranch는 그대로 유지된다.

## 결과

- placeholder User 삭제 후에도 UserBranch가 새 유저에 정상 귀속
- branchIds 빈 배열 현상 해소
- orphanRemoval 설정 자체는 다른 정상 시나리오에서 필요하므로 제거하지 않고, 삭제 경로만 JPQL로 분리하여 부작용을 차단

### 참고

- [id auto increment가 되지 않고 null값으로 전달될 때](https://velog.io/@hansjour/id-auto-increment%EA%B0%80-%EB%90%98%EC%A7%80-%EC%95%8A%EA%B3%A0-null%EA%B0%92%EC%9C%BC%EB%A1%9C-%EC%A0%84%EB%8B%AC%EB%90%A0-%EB%95%8C-engufnq0)
